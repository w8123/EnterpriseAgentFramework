package com.enterprise.ai.control.aicoding.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("control_ai_coding_task_question")
public class AiCodingTaskQuestionEntity {

    @TableId(type = IdType.INPUT)
    private String questionId;
    private String taskId;
    private String title;
    private String body;
    private String optionsJson;
    private String status;
    private String answer;
    private String askedBy;
    private String answeredBy;
    private LocalDateTime askedAt;
    private LocalDateTime answeredAt;
    private LocalDateTime updatedAt;
}
