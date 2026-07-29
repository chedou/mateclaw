package vip.mate.troubleshooting.repository;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import vip.mate.troubleshooting.model.TroubleshootingBaselineEvaluationRunEntity;

@Mapper
public interface TroubleshootingBaselineEvaluationRunMapper
        extends BaseMapper<TroubleshootingBaselineEvaluationRunEntity> {
}
