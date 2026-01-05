package com.example.sampleapp.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SampleRecord {
    private Long clientId;
    private String operationType;
    private UUID transactionId;
    private Long timestamp;
    private EntityType entityType;
}
