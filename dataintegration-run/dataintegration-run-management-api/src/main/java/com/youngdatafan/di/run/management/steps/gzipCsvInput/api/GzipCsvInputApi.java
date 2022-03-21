package com.youngdatafan.di.run.management.steps.gzipCsvInput.api;

import com.youngdatafan.dataintegration.core.model.Result;
import com.youngdatafan.di.run.management.steps.csvinput.vo.FieldVO;
import io.swagger.annotations.Api;
import io.swagger.annotations.ApiOperation;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.pentaho.di.core.exception.KettleException;
import org.pentaho.metastore.api.exceptions.MetaStoreException;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;

@Api(tags = "gzip csv input输入接口")
public interface GzipCsvInputApi {

    @ApiOperation(value = "预览数据", produces = "application/json")
    @PostMapping(value = "/getData")
    Result<Map<String,Object>, Object> getData(@RequestHeader("authorization-userId") String userId
            , @RequestBody String json, @RequestParam String count) throws KettleException, IOException, MetaStoreException;

    @ApiOperation(value = "获取字段", produces = "application/json")
    @PostMapping(value = "/getColumns")
    Result<List<FieldVO>, Object> getColumns(@RequestHeader("authorization-userId") String userId
            , @RequestBody String json) throws KettleException, IOException, MetaStoreException;

}
