package com.example.sampleapp.domain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// TODO: BufferedRecord#record should be a generic type <T> for flexibility
public class BufferedRecord {
    private SampleRecord record;
    private int partition;
    private long offset;
    private long timestamp;
}
