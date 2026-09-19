# TickJob · 分布式任务调度平台

一个「调度中心 + 执行器」分离的轻量级分布式任务调度平台。调度侧用 **哈希时间轮** 驱动触发，
执行侧以 **Spring Boot Starter** 形式交付，业务方「实现一个接口 + 加一个注解」即可接入。

> 设计目标是**把调度这件事的边界划清楚**：什么时候跑、跑在哪台机器上、跑不完怎么办、
> 跑出问题怎么定位。这四件事分别由时间轮、路由策略、阻塞策略、双段日志负责，
> 互不耦合，每一件都能单独替换。

---

## 目录

- [解决的几个真问题](#解决的几个真问题)
- [架构](#架构)
- [核心设计](#核心设计)
- [快速开始](#快速开始)
- [接入执行器](#接入执行器)
- [接口清单](#接口清单)
- [配置项](#配置项)
- [测试](#测试)
- [已知边界与取舍](#已知边界与取舍)

---

## 解决的几个真问题

先说不做什么：它**不是** Quartz 的替代品。Quartz 解决的是「单机内存里的定时器」，
当一个任务需要落在指定机器上、需要多实例防重复、需要知道「上一次到底跑完没有」时，
它就不够用了。TickJob 针对的是这几件事：

| 问题 | 朴素做法的后果 | 本项目的做法 |
| --- | --- | --- |
| 每秒都有任务到点，怎么不被打垮 | 数据库轮询：要么查得不够勤导致触发延迟，要么查得太勤把库打满 | **预读线程 + 时间轮两级调度**，把「查库频率」和「触发精度」解耦 |
| 调度中心部署多个实例，任务会不会跑两遍 | 上分布式锁，多一个中间件依赖和一堆锁过期问题 | **带版本号的乐观锁**抢占，一句条件 UPDATE 定胜负 |
| 某个任务有本地状态，必须固定在某一台机器上 | 在调度中心记一张「任务 → 机器」映射表，还得处理机器下线 | **一致性哈希**以 jobId 为键，映射关系由算法保证，不存状态 |
| 一个任务要处理 1000 万条数据 | 单机跑很久，扩机器也没用 | **分片广播**，向每台执行器下发分片号与总数 |
| 上一次还没跑完，这一次又到点了 | 任务越堆越多，最后内存溢出 | **阻塞策略**：串行排队 / 丢弃后续 / 覆盖执行 |
| 任务「执行了但没有任何记录」 | 日志要先等结果才能写，中间崩了就成黑洞 | **两阶段写入**：先落一行「未回报」，再异步回填结果 |

---

## 架构

```
                        ┌──────────────────────────── 调度中心 tickjob-admin ────────────────────────────┐
                        │                                                                            │
  ┌──────────────┐      │   ┌──────────────┐        ┌──────────────────────────┐                    │
  │  MySQL / H2  │◀─────┼───│  预读线程     │───────▶│      哈希时间轮           │                    │
  │  job_info    │      │   │  每秒扫一次   │        │  100 格 × 100ms = 10s     │                    │
  │  job_log     │      │   │  未来 5s 内   │        │  O(1) 插入 / 单线程推进    │                    │
  └──────────────┘      │   └──────────────┘        └───────────┬──────────────┘                    │
         ▲              │          │ 乐观锁 claim                │ 到点触发                           │
         │              │          │（多实例只有一个能抢到）      ▼                                    │
         │              │          │                  ┌────────────────────┐                         │
         │              │          └─────────────────▶│     JobTrigger      │                         │
         │              │                             │  路由 → 投递 → 日志  │                         │
         │              │                             └─────────┬──────────┘                         │
         │              │                                       │                                    │
         │              │        ┌──────────────────────────────┴───────────────┐                    │
         │              │        │            ExecutorRegistry（内存）           │                    │
         │              │        │        按 appName 分组，心跳倒序排列           │                    │
         │              │        └──────────────────────────────┬───────────────┘                    │
         │              │                                       │                                    │
         │              │  注册/心跳(/api/registry/register)     │ 触发(/run)                         │
         │              │  结果回传(/api/log/report)             │                                    │
         └──────────────┼───────────────────────────────────────┼────────────────────────────────────┘
                        └───────────────────────────────────────┼──────────────────────────────────┘
                                                                │
                    ┌───────────────────────────────────────────┴───────────────────────────┐
                    │                        执行器 tickjob-executor（独立进程）              │
                    │                                                                       │
                    │   ExecutorRpcServer (JDK 内置 HttpServer，不依赖 Web 容器)              │
                    │            │                                                          │
                    │            ▼                                                          │
                    │   JobThreadRepository ── 每个 jobId 一条常驻线程 + 有界队列             │
                    │            │              按 BlockStrategy 决定 排队/丢弃/覆盖           │
                    │            ▼                                                          │
                    │   HanderExecutor (线程池) ── 业务代码在这里跑，Future#get(timeout) 管超时 │
                    │            │                                                          │
                    │            ▼                                                          │
                    │   @JobHandler("xxx") IJobHandler ── 业务方只需要写这个                  │
                    └───────────────────────────────────────────────────────────────────────┘
```

---

## 核心设计

### 1. 为什么是「预读 + 时间轮」两级

单靠数据库轮询有一个绕不过去的矛盾：轮询间隔决定了触发精度，而轮询频率决定了数据库压力。
想精度高就得查得勤，任务量一大库先扛不住。

两级拆分把这个矛盾解开了：

- **预读线程**每秒跑一次范围查询（`status = 1 AND trigger_next_time <= now + 5s`），
  只把它捞成任务列表。这个开销与「有多少任务」**无关**，只与扫描间隔有关。
- **时间轮**接住这批任务，到点就触发。纯内存操作，插入是 `O(1)` 的数组取模，
  不碰任何 IO，精度只取决于 tick（默认 100ms）。

顺带的好处是预读窗口（5s）明显大于预读间隔（1s），单次预读晚了或慢了都不会漏任务。

### 2. 哈希时间轮

`tickjob-common/wheel/TimeWheel.java`

- **槽位**：100 个 `ConcurrentLinkedQueue`，覆盖 100 × 100ms = 10 秒；
- **绕圈**：超出覆盖窗口的任务用 `remainingRounds` 记剩余整圈数，只有当它递减到 0 才真正触发；
- **线程模型**：只有 tick 线程会「取出」任务并递减 `remainingRounds`，其它线程只做「放入」。
  槽位内部是 `ConcurrentLinkedQueue`，入队 / 出队之间有 happens-before 保证，
  所以 `remainingRounds` **不需要 volatile 也不需要一个锁**；
- **容错**：`scheduleAtFixedRate` 一旦遇到未捕获异常就会永久取消定时任务，
  因此 tick 里兜住了 `Throwable` —— 宁可丢一拍，也不能让整个调度停摆；
- **可观测**：单独统计 `lateFired` / `maxDelayMs`，让「轮子慢了」这件事可见。
  这两个指标衡量的是**轮子这一侧**的准时性（含补投的过期任务），
  **不含**工作线程池里的排队时间 —— 后者属于执行侧，由执行日志的耗时字段体现。
  把「调度慢」和「执行慢」混在一个指标里，出问题时就没法定位了。

> 为什么不用 `ScheduledThreadPoolExecutor`：它底层是堆，插入 / 取消都是 `O(log n)`，
> 任务堆积时大量线程被定时器唤醒，精度和吞吐会一起塌。`DelayQueue` 本质也是堆。

### 3. 多实例防重复调度：乐观锁而不是分布式锁

`JobInfoMapper.claim` 是一句带版本号的条件下更新：

```sql
UPDATE job_info
SET schedule_version = schedule_version + 1, trigger_next_time = ?
WHERE id = ? AND schedule_version = ? AND trigger_next_time = ?
```

多个调度中心实例各自跑预读，谁把这句 UPDATE 改出 1 行，谁才有权把任务投进自己的时间轮。
抢不到的实例静默跳过（debug 级别日志），因为它本来就不该是错误。

相比引入分布式锁，这么做少了：一个中间件依赖、锁过期导致的双重触发风险、
以及「持锁进程崩了怎么办」的心智负担。

### 4. 路由策略：返回值是「有序候选列表」

`ExecutorRouter` 刻意不返回单个地址，而是返回**有序候选列表**。这一个约定就让三种需求
不需要各自的机制：

| 返回长度 | 语义 | 用它的策略 |
| --- | --- | --- |
| 1 个 | 只试这一个，失败即整体失败 | `FIRST` / `ROUND` / `RANDOM` / `CONSISTENT_HASH` |
| N 个 | 调度中心按顺序依次尝试 | `FAILOVER` |
| 全部 | 每个都要触发一次 | `SHARDING_BROADCAST` |

故障转移因此不需要状态机：本次触发里失败的地址被放进 `excluded` 集合，重新路由一次即可，
而且**天然不会回头**。

一致性哈希用 100 个虚拟节点 + MD5（取摘要低 4 字节拼成无符号 32 位），
环按节点集合缓存，节点不变时不重建。测试里验证了「摘掉 1 台只迁移约 1/3 的键」。

### 5. 分片广播

调度中心把 `shardIndex / shardTotal` 随触发参数一起下发，执行器在调用业务处理器**之前**
把它们绑到「真正执行业务的那条线程」上，业务代码直接读：

```java
int index = ShardingContext.index();   // 当前分片，从 0 开始
int total = ShardingContext.total();
// 每台执行器各处理 1/total 的数据
```

用 `ThreadLocal` 而不是参数透传，是为了不污染所有业务方法签名；用 `try-finally` 包住
`bind/unbind`，是因为执行器的业务线程来自线程池，不清掉就会把分片号串给下一个任务。
**这一点有专门的测试守着**（`contextIsClearedAfterExecution`）。

### 6. 阻塞策略：SERIAL / DISCARD_LATER / COVER_EARLY

同一个任务的多次触发必须**串行**（否则「上一次跑完没有」这个判断就不成立了），
不同任务之间必须**并行**（一个慢任务不能拖住别人）。实现方式是
**每个 jobId 一条常驻线程 + 有界队列**，比「线程池 + 按任务分组」简单得多；
代价是任务数多时线程数线性增长，所以有**空闲回收**（默认 60s 无活动就回收，下次调度再拉起）。

业务代码不在 `JobThread` 自己的线程里直接跑，而是提交给 `handlerExecutor` 再
`Future#get(timeout)` 等结果。这么做是为了超时控制：直接跑的话唯一的中断手段是
`this.interrupt()`，会把执行循环一起打断；用 Future 则 `cancel(true)` 精确地只中断那一次执行。

`COVER_EARLY` 中断掉的那一次会被记为 `202 被丢弃` 而不是 `500 失败` ——
它是设计内的行为，记成失败会让失败率虚高，告警里混进一堆「正常现象」。

### 7. 两阶段写入的触发日志

执行器需要带着 `logId` 回调，所以日志 ID 必须在投递**之前**就确定，
自然形成「先插一行（`handle_code = 0`）→ 投递 → 回填结果」。这顺带解决了一个更大的问题：

> 投递过程中调度中心崩了，这条日志仍然在库里，不会出现「执行了但没有任何记录」的黑洞。

围绕这个状态还配了两个收尾动作：

- `markNotExecuted`：投递就失败（没有存活执行器、执行器拒绝）的，直接结掉。
  不结掉的话它会永远停在 `handle_code = 0`，看板上显示成「执行中」，把排查方向带偏；
- `markUnreportedAsLost`（`@Scheduled` 对账）：投递成功了但执行器一直没回报的
  （进程被 kill、机器断电），超过阈值标成超时。

`trigger_*` 与 `handle_*` 两段**分开记录**：执行器进程活着但业务抛异常时，
如果不分开，运维会误以为是网络问题。

---

## 快速开始

要求：**JDK 21**、Maven（或用 `mvnw`）。

### 零依赖启动（local profile）

不需要装 MySQL —— 用 H2 内存库，并在调度中心进程内嵌一个执行器实例，
启动后就能看到任务被真实触发、执行、结果回写日志。

```bash
mvn clean package -DskipTests
java -jar tickjob-admin/target/tickjob-admin-1.0.0.jar
```

打开 <http://localhost:8080>，控制台首页能看到时间轮状态与执行器注册情况：

```bash
curl http://localhost:8080/api/monitor/overview
```

```json
{
  "wheel": {"wheelSize":100,"tickMillis":100,"coverageMs":10000,"scheduled":44,"fired":43,"pending":1,"lateFired":8,"maxDelayMs":283},
  "executorCount": 1,
  "jobCount": 4,
  "claimedTotal": 44,
  "logTotal": 43
}
```

`data-h2.sql` 预置了 4 个演示任务（每 5 秒打印 / 分片广播 / 慢任务 / 失败任务），
启动几秒后就能在日志里看到执行结果：

```bash
curl "http://localhost:8080/api/logs?limit=5"
```

接口文档：<http://localhost:8080/swagger-ui.html>
H2 控制台：<http://localhost:8080/h2-console>

### 建一个任务并手工触发

```bash
# 创建：每 30 秒执行一次
curl -X POST http://localhost:8080/api/jobs \
  -H 'Content-Type: application/json' \
  -d '{
    "jobName": "订单超时取消",
    "appName": "tickjob-demo",
    "handlerName": "demoPrintJob",
    "cron": "0 0/30 * * * ?",
    "param": "from-cli",
    "routeStrategy": "ROUND",
    "blockStrategy": "SERIAL_EXECUTION"
  }'

# 预览接下来 3 次触发时间
curl -G http://localhost:8080/api/jobs/preview \
  --data-urlencode 'cron=0 0/5 * * * ?' --data-urlencode 'count=3'

# 立即执行一次（不影响 cron 排期）
curl -X POST http://localhost:8080/api/jobs/1/trigger

# 看这个任务的执行日志
curl "http://localhost:8080/api/logs/job/1?limit=5"

# 停止 / 启动
curl -X POST "http://localhost:8080/api/jobs/1/status?running=false"
```

### 生产形态（dev profile）

执行器**部署为独立进程**，调度中心关掉内嵌执行器：

```bash
# 1) 建表
mysql -u root -p < sql/schema-mysql.sql

# 2) 调度中心（关掉内嵌执行器）
java -jar tickjob-admin/target/tickjob-admin-1.0.0.jar \
  --spring.profiles.active=dev \
  --tickjob.executor.enabled=false

# 3) 业务应用引入 tickjob-executor，配置调度中心地址后启动
```

---

## 接入执行器

业务方只需要两步。

**第一步**，引入依赖：

```xml
<dependency>
    <groupId>com.kong</groupId>
    <artifactId>tickjob-executor</artifactId>
    <version>1.0.0</version>
</dependency>
```

**第二步**，实现接口 + 加注解：

```java
@JobHandler("orderTimeoutCancel")
public class OrderTimeoutCancelHandler implements IJobHandler {

    @Override
    public String execute(String param) {
        // 分片信息可以直接读，线程安全（由执行器负责绑定与清理）
        int shard = ShardingContext.index();
        int total = ShardingContext.total();

        int cancelled = orderService.cancelTimeoutOrders(shard, total);
        // 返回值会落到日志的 handle_msg 字段，出问题时不用登机器翻日志
        return "取消订单 " + cancelled + " 笔";
    }
}
```

配置：

```yaml
tickjob:
  executor:
    enabled: true
    app-name: order-service              # 调度中心按它分组找执行器
    admin-addresses:
      - http://10.0.0.1:8080
      - http://10.0.0.2:8080             # 调度中心可以部署多实例，依次尝试注册
    port: 9999
    registry-interval-seconds: 30
    max-handler-threads: 200
    idle-thread-keep-alive-seconds: 60
    # access-token: xxx                 # 可选，开启后调度中心的请求必须带 TickJob-Token
```

注意：

- 执行器基于 JDK 内置 `com.sun.net.httpserver.HttpServer`，**不依赖 Web 容器**，
  业务应用不是 Web 应用也能用；
- `@JobHandler` 的名字全应用内唯一，**启动期**就会校验重名并拒绝启动；
  handler 名字写错时执行器会在收单时就拒绝，并把本机已注册的 handler 名字一起返回；
- 心跳兼作重新注册，所以调度中心滚动发布期间执行器会自己恢复，不需要人工干预。

---

## 接口清单

### 任务管理（给人用）

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/jobs` | 任务列表（带下次触发时间预览） |
| GET | `/api/jobs/{id}` | 任务详情 |
| POST | `/api/jobs` | 新建任务 |
| PUT | `/api/jobs/{id}` | 修改任务（会重算下次触发时间） |
| DELETE | `/api/jobs/{id}` | 删除任务 |
| POST | `/api/jobs/{id}/trigger` | 手工触发一次 |
| POST | `/api/jobs/{id}/status?running=` | 启动 / 停止 |
| GET | `/api/jobs/preview?cron=&count=` | 预览接下来 N 次触发时间 |
| GET | `/api/jobs/options` | 路由 / 阻塞策略可选值 |

### 日志与监控

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/logs?limit=` | 最近执行日志 |
| GET | `/api/logs/job/{jobId}?limit=` | 某任务的执行日志 |
| GET | `/api/logs/stats` | 成功 / 失败 / 待回报统计 |
| GET | `/api/monitor/overview` | 总览（时间轮 + 执行器 + 日志） |
| GET | `/api/monitor/wheel` | 时间轮运行态快照 |
| GET | `/api/monitor/registry` | 执行器注册表（含失联节点） |

### 内部 RPC（给机器用）

| 方向 | 路径 | 说明 |
| --- | --- | --- |
| 调度中心 → 执行器 | `/run` | 触发一次执行（收单即返回，不等业务） |
| 调度中心 → 执行器 | `/beat` | 心跳探测 |
| 调度中心 → 执行器 | `/idleBeat` | 空转检测（见「已知边界」） |
| 执行器 → 调度中心 | `/api/registry/register` | 注册 / 心跳 |
| 执行器 → 调度中心 | `/api/registry/remove` | 优雅停机时主动摘除 |
| 执行器 → 调度中心 | `/api/log/report` | 执行结果回传 |

响应统一是 `{"code":200,"msg":null,"data":{...},"ok":true}`。
错误时**响应体里的 `code` 与 HTTP 状态码是同一个值**：入参错误 400，服务端故障 500 ——
只返回 200 再在 body 里写错误码，会让网关、监控、重试组件都误以为请求成功。

---

## 配置项

### 调度中心 `tickjob.schedule`

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `pre-read-seconds` | 5 | 预读窗口。必须明显大于预读间隔，否则会漏任务 |
| `pre-read-interval-seconds` | 1 | 预读扫描间隔 |
| `trigger-timeout-seconds` | 5 | 调用执行器 `/run` 的超时。该接口收单即返回，只反映网络往返 |
| `executor-dead-threshold-seconds` | 90 | 执行器失联判定阈值（心跳间隔的 3 倍） |
| `registry-evict-interval-seconds` | 30 | 注册表清理间隔 |
| `log-lost-threshold-seconds` | 180 | 结果回报超时阈值，超过就标记为超时 |
| `log-monitor-interval-seconds` | 60 | 结果对账间隔 |

### 时间轮

固定为 `100 格 × 100ms = 10 秒覆盖窗口`，是预读窗口（5s）的两倍。
留这个余量是为了让「预读晚跑一拍」或「系统短暂卡顿」都不会让任务绕过时间轮被立即触发。
超出窗口的任务会走 `remainingRounds` 绕圈，功能上没问题，但精度会下降。

---

## 测试

```bash
mvn test
```

**132 个用例**，全部不依赖外部服务：

| 模块 | 用例数 | 覆盖内容 |
| --- | --- | --- |
| `tickjob-common` | 79 | Cron 解析（含闰年、跨月跨年、日/周 OR 语义、非法输入）、时间轮（绕圈、批量触发、延迟统计、生命周期）、6 种路由策略、一致性哈希迁移比例、分片上下文线程隔离 |
| `tickjob-executor` | 31 | 处理器注册（重名 / 空名 / 代理子类）、三种阻塞策略、超时中断、上下文绑定与清理、线程仓库懒创建与空闲回收 |
| `tickjob-admin` | 22 | 执行器注册表（心跳顺序、失联剔除、快照）、**端到端链路**（建任务 → 投递 → 执行 → 回写日志） |

端到端用例真的把调度中心跑起来（H2 + 内嵌执行器，独立端口），走完整链路。
这一层刻意不 mock —— 单元测试能证明每一段各自正确，但证明不了它们拼在一起能跑通。
事实上这一层第一次跑就抓出了两个只在跨进程时才会暴露的问题：

1. `handle_cost_ms` 引用了 `LogParam` 上并不存在的属性名，导致**每一次结果回报都返回 500**，
   执行结果从来没真正落过库。编译期发现不了，因为它只在两端字段名对不上时才出现；
2. `currentFuture` 在提交任务**之后**才赋值，任务已经开跑但游标还没挂上，
   这个窗口里到达的取消会打空，`COVER_EARLY` 会静默失效。

时间轮相关用例依赖真实时钟，因此 surefire 配置了 `parallel=none`；
断言里的等待上限一律给到 5 秒以上，宁可多等也不用紧贴预期的超时制造偶发失败。

---

## 已知边界与取舍

主动写清楚，比被问出来好。

- **错过的触发不补跑**。预读用的是 `cron.next(now)` 而不是「从上次触发时间往后推」。
  服务停机 2 小时再启动，这 2 小时里本该触发的次数不会被一次性补上。
  这是刻意的：几百个任务同时补跑会在启动瞬间把执行器打垮，而对账、报表这类任务
  补跑历史时段通常也没有业务意义。真需要补偿的场景应该由业务侧显式设计补偿任务。
- **执行器注册表是内存态，不持久化**。它回答的是「此刻有哪些执行器能接活」，
  本质是易失的运行时视图。落库只会带来「每次路由都要读库」和「实例宕机留下脏记录」两个麻烦。
  后果是**调度中心多实例之间看不到彼此的注册表** —— 但每个执行器会向所有调度中心注册，
  所以任一实例都能看到全量执行器。
- **`/idleBeat` 已实现但调度中心尚未接入**。设计意图是「注册表里找不到某个执行器，
  或者任务要下线时，先问一句『你这边还有它的活没干完吗』，有就保住注册关系」。
  当前 `evictDead` 纯按心跳超时剔除，这个端点还没有调用方。
- **时间轮精度受 tick 限制**，默认 100ms。秒级任务足够，毫秒级任务不适用。
  tick 调小会线性增加空转开销。
- **调度中心本身没有做鉴权**（执行器侧有可选的 `access-token`）。
  它假定部署在内网，管理接口的鉴权应该由网关承担。
- **分片广播要求执行器数量稳定**。触发瞬间的存活列表决定了 `shardTotal`，
  如果广播过程中有执行器上下线，不同分片看到的总数可能不一致。
  分片任务自身需要容忍这种情况（例如按数据主键取模而不是按下标切分）。
- **没有可视化控制台页面**。当前提供的是 REST 接口 + Swagger UI，
  前端看板是下一步的事。

---

## 技术栈

Java 21 · Spring Boot 3.5 · MyBatis · MySQL / H2 · Redis（预留）· JUnit 5

## License

MIT
