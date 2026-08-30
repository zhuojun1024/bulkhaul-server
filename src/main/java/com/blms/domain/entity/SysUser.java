package com.blms.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** 系统用户（含客户/司机账号；username 可为手机号） */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.INPUT)
    private String id;
    private String username;
    private String name;
    private String role;
    private String passwordHash;
    private String phone;
    private String email;
    private String status;
    private LocalDateTime lastLogin;
    private LocalDate createdAt;
    private String customerId;
    private String driverId;
}
