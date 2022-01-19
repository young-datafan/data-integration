package com.youngdatafan.portal.common.group.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Date;

@Data
@NoArgsConstructor
public class DpPortalGroupDTO {
    /**
     * 分组编号
     */
    private String groupId;

    /**
     * 分组名称
     */
    private String groupName;

    /**
     * 分组描述
     */
    private String describe;

    /**
     * 分组类型	基本模型、业务模型、探索脚本、集成脚本、报表
     */
    private String groupType;

    /**
     * 分组类型名称
     */
    private String groupTypeName;

    /**
     * 分组排序
     */
    private Integer groupOrder;

    /**
     * 是否启用	T-启用；F-未启用；
     */
    private String enabled;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 修改时间
     */
    private Date updateTime;

    /**
     * 创建者
     */
    private String createUserId;

    /**
     * 创建用户名
     */
    private String createUserName;

}