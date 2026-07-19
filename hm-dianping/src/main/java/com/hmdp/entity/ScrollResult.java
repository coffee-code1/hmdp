package com.hmdp.entity;

import lombok.Data;

import java.util.List;

@Data
public class ScrollResult {
    private List<?>list;
    private Long mintime;
    private Integer offset;
}
