package com.rsdp.officeproduct.importer;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan({"com.rsdp.officeproduct.importer.mapper", "com.rsdp.officeproduct.importer.office.mapper"})
public class OfficeProductImportApplication {

    public static void main(String[] args) {
        SpringApplication.run(OfficeProductImportApplication.class, args);
    }
}
