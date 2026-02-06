package com.example.sampleapp.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BufferedRecord {
    private SampleRecord record;
    private int partition;
    private long offset;
    private long timestamp;
}
