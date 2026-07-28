package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.troubleshooting.intake.TroubleshootingIntakeSessionEntity;

/** Mapper kept under repository so the application-wide MapperScan registers it. */
@Mapper
public interface TroubleshootingIntakeSessionMapper
        extends BaseMapper<TroubleshootingIntakeSessionEntity> {
}
