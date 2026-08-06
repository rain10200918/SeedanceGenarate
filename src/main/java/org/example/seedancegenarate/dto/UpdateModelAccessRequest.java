package org.example.seedancegenarate.dto;

import lombok.Data;

/** PUT /api/admin/models/{model} 请求体：设置该模型开/关。 */
@Data
public class UpdateModelAccessRequest {
    private Boolean open;
}
