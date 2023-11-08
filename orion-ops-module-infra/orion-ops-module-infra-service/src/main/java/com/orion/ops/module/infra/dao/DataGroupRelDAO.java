package com.orion.ops.module.infra.dao;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.orion.ops.framework.mybatis.core.mapper.IMapper;
import com.orion.ops.module.infra.entity.domain.DataGroupRelDO;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * 数据分组关联 Mapper 接口
 *
 * @author Jiahang Li
 * @version 1.0.0
 * @since 2023-11-7 18:44
 */
@Mapper
public interface DataGroupRelDAO extends IMapper<DataGroupRelDO> {

    /**
     * 通过 groupId 删除
     *
     * @param idList idList
     * @return effect
     */
    default int deleteByGroupId(List<Long> idList) {
        LambdaQueryWrapper<DataGroupRelDO> wrapper = this.lambda()
                .in(DataGroupRelDO::getGroupId, idList);
        return this.delete(wrapper);
    }

}
