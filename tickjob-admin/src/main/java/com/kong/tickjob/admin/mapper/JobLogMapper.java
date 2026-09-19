package com.kong.tickjob.admin.mapper;

import com.kong.tickjob.admin.domain.JobLog;
import com.kong.tickjob.common.protocol.LogParam;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface JobLogMapper {

    /** 投递前先落一行，此时 handle_code = 0 表示结果尚未回报 */
    @Insert("""
            INSERT INTO job_log (job_id, job_name, app_name, executor_address,
                                 shard_index, shard_total, trigger_time,
                                 trigger_code, trigger_msg, handle_code, handle_cost_ms, created_at)
            VALUES (#{jobId}, #{jobName}, #{appName}, #{executorAddress},
                    #{shardIndex}, #{shardTotal}, #{triggerTime,jdbcType=TIMESTAMP},
                    0, NULL, 0, 0, CURRENT_TIMESTAMP)
            """)
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(JobLog log);

    /** 调度这一跳的结果：成功投递 / 全部候选执行器都失败 */
    @Update("""
            UPDATE job_log
            SET trigger_code = #{triggerCode}, trigger_msg = #{triggerMsg}
            WHERE id = #{id}
            """)
    int updateTrigger(@Param("id") Long id,
                      @Param("triggerCode") Integer triggerCode,
                      @Param("triggerMsg") String triggerMsg);

    /**
     * 执行结果回报。
     *
     * <p>带 {@code AND handle_code = 0}：执行器因为网络抖动重试上报时，
     * 第二次会被这条条件挡掉，保证结果「只被写一次」。</p>
     *
     * <p>{@code handle_time} 由调用方把执行器上报的 epoch 毫秒转成 {@code LocalDateTime} 后传入，
     * 而不是用 {@code CURRENT_TIMESTAMP} —— 后者只能得到「回报到达时刻」，
     * 网络抖动时它会比真实执行结束时刻晚，排查耗时问题时会把人带偏。
     * 也不在 SQL 里做时区/毫秒转换：那需要各数据库方言，而这里根本不必付这个代价。</p>
     */
    @Update("""
            UPDATE job_log
            SET handle_time = #{handleTime,jdbcType=TIMESTAMP},
                handle_cost_ms = #{log.handleCostMs},
                handle_code = #{log.handleCode},
                handle_msg = #{log.handleMsg}
            WHERE id = #{log.logId} AND handle_code = 0
            """)
    int updateHandle(@Param("log") LogParam log, @Param("handleTime") LocalDateTime handleTime);

    /**
     * 把「触发了但压根没送到执行器」的日志直接结掉。
     *
     * <p>不结掉的话它会一直停在 {@code handle_code = 0}，看板上显示成「执行中」，
     * 而实际上业务代码一行都没跑 —— 这是最容易误导排查方向的一种状态。</p>
     */
    @Update("""
            UPDATE job_log
            SET handle_code = #{code}, handle_msg = #{msg}, handle_time = CURRENT_TIMESTAMP
            WHERE id = #{id} AND handle_code = 0
            """)
    int markNotExecuted(@Param("id") Long id, @Param("code") Integer code, @Param("msg") String msg);

    /**
     * 对账：把「投递出去但执行器一直没回报」的日志标成超时。
     *
     * <p>执行器进程被 kill、机器断电时不会有任何回调，日志会永远停在 {@code handle_code = 0}。
     * 不主动扫一遍，看板上就会显示成「执行中」，把真正的问题掩盖掉。</p>
     */
    @Update("""
            UPDATE job_log
            SET handle_code = #{code},
                handle_msg = #{msg},
                handle_time = #{now,jdbcType=TIMESTAMP}
            WHERE handle_code = 0
              AND trigger_code = 200
              AND trigger_time < #{deadline,jdbcType=TIMESTAMP}
            """)
    int markUnreportedAsLost(@Param("deadline") LocalDateTime deadline,
                             @Param("now") LocalDateTime now,
                             @Param("code") Integer code,
                             @Param("msg") String msg);

    @Select("""
            SELECT * FROM job_log
            WHERE job_id = #{jobId}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<JobLog> findRecentByJob(@Param("jobId") Long jobId, @Param("limit") int limit);

    @Select("""
            SELECT * FROM job_log
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<JobLog> findRecent(@Param("limit") int limit);

    @Select("""
            SELECT * FROM job_log
            WHERE handle_code = #{handleCode}
            ORDER BY id DESC
            LIMIT #{limit}
            """)
    List<JobLog> findByHandleCode(@Param("handleCode") Integer handleCode, @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM job_log")
    long count();

    @Select("SELECT COUNT(*) FROM job_log WHERE handle_code = #{handleCode}")
    long countByHandleCode(@Param("handleCode") Integer handleCode);
}
