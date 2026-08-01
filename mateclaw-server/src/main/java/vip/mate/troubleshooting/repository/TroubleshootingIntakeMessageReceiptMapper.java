package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.troubleshooting.intake.TroubleshootingIntakeMessageReceiptEntity;

/** Mapper kept under repository so the application-wide MapperScan registers it. */
@Mapper
public interface TroubleshootingIntakeMessageReceiptMapper
        extends BaseMapper<TroubleshootingIntakeMessageReceiptEntity> {
}
