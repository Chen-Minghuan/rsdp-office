package com.rsdp.officeproduct.importer.dto.spatial;

import lombok.Data;

@Data
public class SpatialResult<T> {

    private int code;
    private String message;
    private T data;

    public static <T> SpatialResult<T> ok(T data) {
        SpatialResult<T> r = new SpatialResult<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    public static <T> SpatialResult<T> error(int code, String message, T data) {
        SpatialResult<T> r = new SpatialResult<>();
        r.setCode(code);
        r.setMessage(message);
        r.setData(data);
        return r;
    }
}
