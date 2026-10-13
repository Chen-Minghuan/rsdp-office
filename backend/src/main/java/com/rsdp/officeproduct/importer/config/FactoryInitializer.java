package com.rsdp.officeproduct.importer.config;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.rsdp.officeproduct.importer.entity.FactoryMaster;
import com.rsdp.officeproduct.importer.mapper.FactoryMasterMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class FactoryInitializer implements ApplicationRunner {

    private final FactoryMasterMapper factoryMasterMapper;
    private final FactoryProperties factoryProperties;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        FactoryMaster existing = factoryMasterMapper.selectOne(
            new QueryWrapper<FactoryMaster>().eq("factory_code", factoryProperties.getFactoryCode()));
        if (existing != null) {
            factoryProperties.setFactoryId(existing.getId());
            log.info("使用已有工厂: {}({})", existing.getFactoryName(), existing.getFactoryCode());
            return;
        }
        FactoryMaster factory = new FactoryMaster();
        factory.setFactoryCode(factoryProperties.getFactoryCode());
        factory.setFactoryName(factoryProperties.getFactoryName());
        factory.setContactInfo("{}");
        factoryMasterMapper.insert(factory);
        factoryProperties.setFactoryId(factory.getId());
        log.info("初始化默认工厂: {}({})", factory.getFactoryName(), factory.getFactoryCode());
    }
}
