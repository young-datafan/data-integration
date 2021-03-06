package com.youngdatafan.portal.model.management.forward.service.impl;

import com.youngdatafan.portal.model.management.basicmodel.mapper.BasiceModelMapper;
import com.youngdatafan.portal.model.management.businessmodel.mapper.BusinessModelMetadataMapper;
import com.youngdatafan.portal.model.management.datasource.entity.Datasource;
import com.youngdatafan.portal.model.management.datasource.mapper.DatasourceMapper;
import com.youngdatafan.portal.model.management.forward.service.SupersetForwardService;
import com.youngdatafan.portal.model.management.forward.superset.TableColumns;
import com.youngdatafan.portal.model.management.superset.vo.SupersetVO;
import com.fasterxml.jackson.databind.JsonNode;
import org.pentaho.di.core.encryption.KettleTwoWayPasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.List;

/**
 * <p>****************************************************************************</p>
 * <ul style="margin:15px;">
 * <li>Description : description</li>
 * <li>Version     : 1.0</li>
 * <li>Creation    : 2019/12/23 1:25 PM</li>
 * <li>Author      : ksice_xt</li>
 * </ul>
 * <p>****************************************************************************</p>
 */
@Service
public class SupersetForwardServiceImpl implements SupersetForwardService {

    public static final KettleTwoWayPasswordEncoder PASSWORD_ENCODER = new KettleTwoWayPasswordEncoder();

    @Autowired
    RestTemplate restTemplate;

    @Value("${superset.sqllabUrl}")
    private String sqllabUrl;

    @Value("${superset.getAllDataSources}")
    private String getAllDataSourcesUrl;

    @Value("${superset.forwardUrl}")
    private String forwardUrl;

    @Value("${superset.sqlForwardUrl}")
    private String sqlForwardUrl;

    @Resource
    BusinessModelMetadataMapper adminBusinessModelMetadataMapper;

    @Resource
    DatasourceMapper adminDatasourceMapper;

    @Resource
    BasiceModelMapper adminBasicModelMapper;


    @Override
    public void execute(HttpServletRequest req, HttpServletResponse resp, SupersetVO supersetVO) {

        try {

            String dbId = null;

            String sql = PASSWORD_ENCODER.decode(supersetVO.getSql(), false);

            System.out.println(sql + "122222");
            System.out.println(supersetVO + "111");

//            String businessModelName = URLDecoder.decode(supersetVO.getBusinessModelName(), "UTF-8");

//            Datasource datasource = adminDatasourceMapper.selectDataSourceByBusinessName(businessModelName);

            Datasource datasource = adminDatasourceMapper.selectByPrimaryKey(supersetVO.getDatasourceId());

            //??????????????????schema
//            String table_schema = adminBasicModelMapper.selectAdminBasicModelByBusiname(businessModelName);

            List<TableColumns> list = new ArrayList<>();

            if (!StringUtils.isEmpty(datasource)) {
                //??????????????????????????????superset????????????dbid;
                dbId = getAllDataSources(datasource.getDsName());
            }
            if (org.apache.commons.lang.StringUtils.isEmpty(dbId)) {
                throw new RuntimeException("?????????????????????");
            }


            String columns = URLDecoder.decode(req.getParameter("columns"), "UTF-8");

            String[] cloum = columns.split(",");


//            Map<String, String> columnsMap = new HashMap<>();

            for (String c : cloum
            ) {
                TableColumns tableColumns = new TableColumns();

                String[] nameAndChinese = c.split(":");

                tableColumns.setVerbose_name(nameAndChinese[1]);

                tableColumns.setName(nameAndChinese[0]);

                String columnType = nameAndChinese[2].toUpperCase();

                tableColumns.setType(columnType);

                if (columnType.equals("DATE") || columnType.equals("DATETIME")
                        || columnType.equals("TIMESTAMP")) {
                    tableColumns.setIs_date(true);
                }

                list.add(tableColumns);

            }


//            List<XmlBean> list1 = adminBusinessModelMetadataMapper.findModelMetaDataByName(businessModelName, columnsMap.keySet());


//            for (Map.Entry<String, String> m : columnsMap.entrySet()) {
//
//                TableColumns tableColumns = new TableColumns();
//
//                tableColumns.setName(m.getKey());
//
//                for (XmlBean x : list1) {
//
//                    if (m.getKey().equals(x.getColumn())) {
//                        tableColumns.setType("String");
//                        if (x.getColumnType().equals("date") || x.getColumnType().equals("datetime")
//                                || x.getColumnType().equals("timestamp")) {
//                            tableColumns.setIs_date(true);
//                        }
//                    }
//                }
//
//                tableColumns.setVerbose_name(m.getValue());
//                list.add(tableColumns);
//            }

            String tableId = getTableId(dbId, null, sql, list).asText();

            sqlForward(resp, tableId);
//            String dbid = getAllDataSources("localhost");


        } catch (UnsupportedEncodingException e) {
            e.getMessage();
        }
    }

    /**
     * ?????????id,???????????????id??????????????????id?????????????????????????????????
     *
     * @return
     */
    public JsonNode getTableId(String dbId, String schema, String sql, List<TableColumns> list) {

        String data = "{\"dbId\":" + dbId + ",\"schema\":\"" + schema + "\",\"sql\":\"" + sql + "\",\"templateParams\":\"{}\",\"datasourceName\":" + System.currentTimeMillis() + ",\"columns\":" + list + "}";
        System.out.println(data);
        MultiValueMap<String, String> map = new LinkedMultiValueMap<String, String>();
        map.add("data", data);

        System.out.println("data????????????" + map.get("data"));
        ResponseEntity<JsonNode> responseEntity = restTemplate.postForEntity(sqllabUrl, map, JsonNode.class);
        JsonNode jsonNode = responseEntity.getBody();
        return jsonNode.get("table_id");


    }

    /**
     * ????????????????????????????????????dbid?????????????????????
     */
    public String getAllDataSources(String dataSourceName) {
        JsonNode jsonNode = restTemplate.getForObject(getAllDataSourcesUrl, JsonNode.class);
        JsonNode dataSourceResult = jsonNode.get("result");
        for (JsonNode dataSource : dataSourceResult) {
            if (dataSourceName.equals(dataSource.get("database_name").asText())) {
                return dataSource.get("id").asText();
            }
        }
        return null;
    }


    /**
     * ?????????????????????????????????
     * http://localhost:8088/superset/explore/table/92/
     *
     * @param response
     * @param tableId
     */
    public void forward(HttpServletResponse response, String tableId) {
        try {
            response.sendRedirect(forwardUrl + tableId);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    /**
     * ??????sql???????????????????????????,????????????
     * http://localhost:8088/superset/explore/?form_data={"datasource":"106__table","metrics":[],"groupby":[],"viz_type":"table","since":"100 years ago","all_columns":[],"row_limit":100}
     *
     * @param response
     */
    public void sqlForward(HttpServletResponse response, String tabelId) {
        try {
            String url = sqlForwardUrl + "{\"datasource\":\"" + tabelId + "__table\",\"metrics\":[],\"groupby\":[],\"viz_type\":\"table\",\"since\":\"100 years ago\",\"all_columns\":[],\"row_limit\":100}";

            System.out.println(url + "url");
            response.sendRedirect(url);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

}
