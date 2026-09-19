package com.kong.tickjob.admin.mapper;

import com.kong.tickjob.admin.domain.JobInfo;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface JobInfoMapper {

    @Select("SELECT * FROM job_info ORDER BY id DESC")
    List<JobInfo> findAll();

    @Select("SELECT * FROM job_info WHERE id = #{id}")
    JobInfo findById(@Param("id") Long id);

    @Select("SELECT * FROM job_info WHERE job_name = #{jobName}")
    JobInfo findByName(@Param("jobName") String jobName);

    @Select("SELECT COUNT(*) FROM job_info")
    long count();

    /**
     * 预读查询：捞出「运行中、且下次触发时间落在预读窗口内」的任务。
     *
     * <p>走 {@code idx_job_scan(status, trigger_next_time)} 索引做范围扫描，
     * 任务数再多也只扫窗口内的那几条。</p>
     */
    @Select("""
            SELECT * FROM job_info
            WHERE status = 1
              AND trigger_next_time IS NOT NULL
              AND trigger_next_time <= #{windowEnd}
            ORDER BY trigger_next_time ASC
            """)
    List<JobInfo> findDueBefore(@Param("windowEnd") LocalDateTime windowEnd);

    @Insert("""
            INSERT INTO job_info (job_name, app_name, handler_name, cron, param,
                                  route_strategy, block_strategy, timeout_seconds, retry_times,
                                  status, schedule_version, trigger_next_time, remark,
                                  created_at, updated_at)
            VALUES (#{jobName}, #{appName}, #{handlerName}, #{cron}, #{param},
                    #{routeStrategy}, #{blockStrategy}, #{timeoutSeconds}, #{retryTimes},
                    #{status}, 0, #{triggerNextTime,jdbcType=TIMESTAMP}, #{remark},
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JobInfo job);

    /**
     * 全量更新任务定义。
     *
     * <p>顺手把 {@code schedule_version + 1}：任务定义一改，正在轮上的旧触发就应当视为失效，
     * 版本号变化让「预读线程手里那份旧数据」的抢占更新自然失败。</p>
     */
    @Update("""
            UPDATE job_info
            SET job_name = #{jobName},
                app_name = #{appName},
                handler_name = #{handlerName},
                cron = #{cron},
                param = #{param},
                route_strategy = #{routeStrategy},
                block_strategy = #{blockStrategy},
                timeout_seconds = #{timeoutSeconds},
                retry_times = #{retryTimes},
                status = #{status},
                trigger_next_time = #{triggerNextTime,jdbcType=TIMESTAMP},
                remark = #{remark},
                schedule_version = schedule_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int update(JobInfo job);

    /**
     * 抢占一次触发权 —— 整个调度链路上最关键的一条 SQL。
     *
     * <p>调度中心可以多实例部署，每个实例都有自己的预读线程和时间轮。如果不加约束，
     * N 个实例会在同一时刻把同一个任务各触发一次。</p>
     *
     * <p>这里用「读到的版本号」做条件更新：只有一个实例能把自己读到的 {@code version}
     * 换成 {@code version + 1}，其余实例的 {@code WHERE schedule_version = ?} 匹配不到行，
     * 返回更新行数 0，于是安静地放弃。没必要引入分布式锁 —— 一次带条件的 UPDATE
     * 本身就是原子的，而且不需要额外的锁服务和锁续期逻辑。</p>
     *
     * @return 1 表示抢到了这次触发权
     */
    @Update("""
            UPDATE job_info
            SET trigger_last_time = #{expectedTime,jdbcType=TIMESTAMP},
                trigger_next_time = #{newTime,jdbcType=TIMESTAMP},
                schedule_version = schedule_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id} AND schedule_version = #{version}
            """)
    int claim(@Param("id") Long id,
              @Param("version") Long version,
              @Param("expectedTime") LocalDateTime expectedTime,
              @Param("newTime") LocalDateTime newTime);

    /** 单独改状态：停止 / 启停切换，同时把下次触发时间重算或清空 */
    @Update("""
            UPDATE job_info
            SET status = #{status},
                trigger_next_time = #{nextTime,jdbcType=TIMESTAMP},
                schedule_version = schedule_version + 1,
                updated_at = CURRENT_TIMESTAMP
            WHERE id = #{id}
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("status") Integer status,
                     @Param("nextTime") LocalDateTime nextTime);

    @Delete("DELETE FROM job_info WHERE id = #{id}")
    int deleteById(@Param("id") Long id);
}
