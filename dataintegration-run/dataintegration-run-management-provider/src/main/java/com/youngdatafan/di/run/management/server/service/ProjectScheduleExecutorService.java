package com.youngdatafan.di.run.management.server.service;


import com.youngdatafan.dataintegration.core.exception.DpException;
import com.youngdatafan.dataintegration.core.exception.ValidationException;
import com.youngdatafan.dataintegration.core.model.Result;
import com.youngdatafan.dataintegration.core.util.StatusCode;
import com.youngdatafan.dataintegration.core.util.UUIDUtils;
import com.youngdatafan.dataintegration.core.util.json.JSONLinkedObject;
import com.youngdatafan.dataintegration.core.util.json.XML;
import com.youngdatafan.di.run.management.server.bean.ProjectExecutor;
import com.youngdatafan.di.run.management.server.dto.ProjectExecutorDTO;
import com.youngdatafan.di.run.management.server.dto.ProjectExecutorStepDTO;
import com.youngdatafan.di.run.management.server.entity.DpDeProjectExecHistory;
import com.youngdatafan.di.run.management.server.mapper.DpDeProjectExecHistoryMapper;
import com.youngdatafan.di.run.management.server.trans.LogBrowser;
import com.youngdatafan.di.run.management.server.trans.TransExecutor;
import com.youngdatafan.di.run.management.server.util.ProjectExecuteEnv;
import com.youngdatafan.di.run.management.server.util.ProjectExecuteStatus;
import com.youngdatafan.di.run.management.server.vo.ProjectExecutorParam;
import com.youngdatafan.di.run.management.server.websocket.ProjectExecuteCallback;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.RandomStringUtils;
import org.apache.commons.lang.StringEscapeUtils;
import org.pentaho.di.core.exception.KettleMissingPluginsException;
import org.pentaho.di.core.exception.KettleXMLException;
import org.pentaho.di.core.logging.LogLevel;
import org.pentaho.di.core.variables.Variables;
import org.pentaho.di.core.xml.XMLHandler;
import org.pentaho.di.trans.TransExecutionConfiguration;
import org.pentaho.di.trans.TransMeta;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMetaDataCombi;
import org.pentaho.di.trans.step.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

/**
 * ????????????????????????(??????)
 *
 * @author gavin
 * @since 2020/2/13 10:53 ??????
 */
@Service
public class ProjectScheduleExecutorService {

    /**
     * ??????id??????key ??????
     */
    public static final String INSTANCE_ID_CACHE_KEY_PREFIX = "instanceId_";

    public static final String CACHE_PREFIX = "di_re_";

    /**
     * ????????????????????????map??????key
     */
    public static final String DI_SCHEDULE_EXEC_HEARTBEAT_CACHE_KEY = "di_schedule_exec_heartbeat";

    private static final Logger logger = LoggerFactory.getLogger(ProjectScheduleExecutorService.class);

    /**
     * ????????????????????????map
     */
    private final ConcurrentHashMap<String, ProjectExecutor> executorMap = new ConcurrentHashMap<>();

    private final RedisTemplate<String, String> redisTemplate;

    private final DpDeProjectExecHistoryMapper projectExecHistoryMapper;

    @Value("${spring.cloud.consul.discovery.instance-id}")
    private String instanceId;

    /**
     * ???????????????????????????????????????
     */
    @Value("${dp.project.restExecute.heartbeatInterval:30}")
    private int heartbeatInterval;

    /**
     * ???????????????????????????????????????
     */
    @Value("${dp.project.restExecute.heartbeatTimeout:180}")
    private int heartbeatTimeout;

    /**
     * ??????????????????????????????
     */
    @Value("${dp.project.restExecute.refreshRateMs:800}")
    private int refreshRateMs;

    /**
     * ???????????????
     */
    @Value("${dp.project.restExecute.tmpFolder:./tmp_rest/}")
    private String tmpFolder;

    /**
     * ??????????????????
     */
    @Value("${dp.project.restExecute.tmpFileSuffix:.xml}")
    private String tmpFileSuffix;

    @Value("${dp.project.restExecute.deleteTmpFile:true}")
    private boolean deleteTmpFile;

    @Value("${kettle.engine.name:Pentaho local}")
    private String engineName;

    @Autowired
    public ProjectScheduleExecutorService(RedisTemplate<String, String> redisTemplate, DpDeProjectExecHistoryMapper projectExecHistoryMapper) {
        this.redisTemplate = redisTemplate;
        this.projectExecHistoryMapper = projectExecHistoryMapper;
    }

    /**
     * ???????????????
     *
     * @param projectId ??????id
     */
    public boolean stop(String projectId) {
        final String cacheKey = CACHE_PREFIX + projectId;
        String executorId = redisTemplate.opsForValue().get(cacheKey);
        if (executorId == null) {
            logger.info("???????????????????????????ID:{}", executorId);
            return false;
        }

        // ????????????
        redisTemplate.delete(Arrays.asList(cacheKey, INSTANCE_ID_CACHE_KEY_PREFIX + projectId));
        // ??????redis????????????
        redisTemplate.boundHashOps(DI_SCHEDULE_EXEC_HEARTBEAT_CACHE_KEY).delete(projectId);

        ProjectExecutor projectExecutor = executorMap.get(executorId);
        if (projectExecutor == null) {
            logger.info("??????????????????????????????ID:{}", executorId);
            return false;

        } else {
            logger.info("?????????????????????????????????ID:{}", executorId);
            projectExecutor.getTransExecutor().stop();
            return true;
        }
    }

    /**
     * ???????????????????????????
     *
     * @param projectId ??????id
     */
    public void checkExists(String projectId) {
        final String cacheKey = CACHE_PREFIX + projectId;
        if (redisTemplate.opsForValue().get(cacheKey) != null) {
            // ?????????????????????????????????
            final String lastHeartbeatTime = (String) redisTemplate.boundHashOps(DI_SCHEDULE_EXEC_HEARTBEAT_CACHE_KEY).get(projectId);

            if (lastHeartbeatTime != null &&
                (System.currentTimeMillis() - Long.parseLong(lastHeartbeatTime)) / 1000 >= heartbeatTimeout) {
                throw new ValidationException(StatusCode.CODE_10010, "?????????????????????");
            }
        }
    }

    /**
     * ????????????????????????
     *
     * @param executorParamVO ????????????
     * @param executeCallback ??????????????????
     */
    public void executeByFile(StringBuilder log, String projectFile, ProjectExecutorParam executorParamVO
        , ProjectExecuteCallback executeCallback) throws Exception {
        executorParamVO.setStartTime(new Date());
        final String executorId = executorParamVO.getExecutorId();

        final String projectId = executorParamVO.getProjectId();
        final String cacheKey = CACHE_PREFIX + projectId;

        // ??????????????????????????????????????????
        redisTemplate.boundValueOps(cacheKey).set(executorId, 1, TimeUnit.DAYS);
        // ???????????????????????????
        redisTemplate.boundValueOps(INSTANCE_ID_CACHE_KEY_PREFIX + projectId).set(instanceId, 1, TimeUnit.DAYS);
        // ?????????????????????????????????map???
        redisTemplate.boundHashOps(DI_SCHEDULE_EXEC_HEARTBEAT_CACHE_KEY).put(projectId, String.valueOf(System.currentTimeMillis()));

        final long currentTimeMillis = System.currentTimeMillis();
        // ??????????????????
        final DpDeProjectExecHistory dpDeProjectExecHistory = saveExecuteHistory(executorParamVO);
        TransExecutor executor = null;

        try {
            // ??????
            executor = execute(log, executorId, projectFile, executorParamVO, executeCallback);

        } catch (Exception e) {
            logger.error("??????????????????", e);

            // ??????????????????
            Result<ProjectExecutorDTO, Object> webSocketResponse = Result.success(ProjectExecutorDTO.builder()
                .executorId(executorId)
                // ??????????????????
                .log("?????????????????????\n" + e.getMessage()).build());

            // ????????????
            executeCallback.onMessage("/runningState", webSocketResponse);

            // ??????map???????????????????????????
            ProjectExecutor projectExecutor = executorMap.remove(executorId);
            if (projectExecutor != null && projectExecutor.getTransExecutor() != null) {
                projectExecutor.getTransExecutor().stop();
            }

            throw e;

        } finally {
            // ??????map???????????????????????????
            executorMap.remove(executorId);
            logger.info("???????????????????????? executorId: {}", executorId);

            if (deleteTmpFile) {
                File tmpFile = new File(tmpFolder, executorId + tmpFileSuffix);
                if (tmpFile.exists()) {
                    logger.info("????????????????????? filePath: {}, ????????????:{}", tmpFile.getPath(), tmpFile.delete());
                }
            }

            // ??????redis??????
            redisTemplate.delete(cacheKey);
            // ??????redis????????????
            redisTemplate.boundHashOps(DI_SCHEDULE_EXEC_HEARTBEAT_CACHE_KEY).delete(projectId);

            // ????????????????????????
            updateHistoryStatus(currentTimeMillis, dpDeProjectExecHistory, executor);
        }
    }

    private void updateHistoryStatus(long currentTimeMillis, DpDeProjectExecHistory dpDeProjectExecHistory, TransExecutor execute) {
        DpDeProjectExecHistory updateHistory = new DpDeProjectExecHistory();
        updateHistory.setId(dpDeProjectExecHistory.getId());

        if (execute != null && execute.getTrans().isStopped()) {
            updateHistory.setStatus(ProjectExecuteStatus.TERMINATIN.name());
        } else {
            updateHistory.setStatus(ProjectExecuteStatus.END.name());
        }
        updateHistory.setEndTime(new Date());
        updateHistory.setExecSecond((int) ((System.currentTimeMillis() - currentTimeMillis) / 1000));
        projectExecHistoryMapper.updateByPrimaryKeySelective(updateHistory);
    }

    private DpDeProjectExecHistory saveExecuteHistory(ProjectExecutorParam executorParamVO) {
        final DpDeProjectExecHistory dpDeProjectExecHistory = new DpDeProjectExecHistory();
        dpDeProjectExecHistory.setId(UUIDUtils.nextId());
        dpDeProjectExecHistory.setProjectId(executorParamVO.getProjectId());
        dpDeProjectExecHistory.setUserId(executorParamVO.getUserId());
        dpDeProjectExecHistory.setUserName(executorParamVO.getUserName());
        dpDeProjectExecHistory.setExecEnv(ProjectExecuteEnv.JC_YXPT.name());
        dpDeProjectExecHistory.setStatus(ProjectExecuteStatus.RUNNING.name());
        dpDeProjectExecHistory.setStartTime(new Date());
        projectExecHistoryMapper.insert(dpDeProjectExecHistory);

        return dpDeProjectExecHistory;
    }

    /**
     * ??????
     *
     * @param executorId      ???????????????
     * @param executorParamVO ????????????
     * @param executeCallback ??????????????????
     * @return ?????????
     */
    private TransExecutor execute(StringBuilder log, String executorId, String projectFile, ProjectExecutorParam executorParamVO
        , ProjectExecuteCallback executeCallback) throws Exception {
        // ????????????
        TransExecutor transExecutor = start(executorId, projectFile, executorParamVO);
        // ??????id
        final String projectId = executorParamVO.getProjectId();
        // ??????????????????
        long lastHeartbeatTime = System.currentTimeMillis();

        ProjectExecutor projectExecutor = new ProjectExecutor(transExecutor, null);
        // ???TransExecutor?????????map?????????
        executorMap.put(executorId, projectExecutor);

        // ???????????????
        LogBrowser logBrowser = new LogBrowser(transExecutor);

        // ????????????????????????
        while (!transExecutor.isFinishedOrStopped()) {

            // ???????????????????????????????????????
            List<ProjectExecutorStepDTO> executorSteps = buildStepStatus(projectExecutor);
            // ??????????????????
            Result<ProjectExecutorDTO, Object> webSocketResponse = Result.success(ProjectExecutorDTO.builder()
                .executorId(executorId)
                .executorSteps(executorSteps)
                // ??????????????????
                .log(logBrowser.getRealTimeLog()).build());

            // ????????????
            executeCallback.onMessage("/runningState", webSocketResponse);

            try {
                TimeUnit.MILLISECONDS.sleep(refreshRateMs);
            } catch (InterruptedException e) {
                logger.error("execute InterruptedException.");
                break;
            }

            final long timeMillis = System.currentTimeMillis();
            if ((timeMillis - lastHeartbeatTime) / 1000 >= heartbeatInterval) {
                // ??????????????????????????????map???
                redisTemplate.boundHashOps(DI_SCHEDULE_EXEC_HEARTBEAT_CACHE_KEY).put(projectId, String.valueOf(timeMillis));

                // ??????????????????
                lastHeartbeatTime = timeMillis;
            }
        }

        //????????????????????????
        final List<ProjectExecutorStepDTO> executorSteps = buildStepStatus(projectExecutor);
        // ??????????????????
        Result<ProjectExecutorDTO, Object> webSocketResponse = Result.success(
            ProjectExecutorDTO.builder()
                .executorId(executorId)
                .executorSteps(executorSteps)
                .transFinished(true)
                // ??????????????????
                .log(logBrowser.getRealTimeLog()).build());
        // ????????????
        executeCallback.onMessage("/runningState", webSocketResponse);

        logger.info("??????????????????????????????id:{}", executorId);

        // ??????????????????
        if (transExecutor.getTrans() != null && transExecutor.getTrans().getErrors() > 0) {
            logger.error("??????????????????");
            // ??????????????????
            throw new DpException(StatusCode.CODE_10010.getCode(), log.toString());
        }
        return transExecutor;
    }

    /**
     * ????????????????????????
     *
     * @param projectExecutor ProjectExecutor
     * @return ArrayList
     */
    private List<ProjectExecutorStepDTO> buildStepStatus(ProjectExecutor projectExecutor) {
        List<StepMetaDataCombi> steps = projectExecutor.getTransExecutor().getTrans().getSteps();
        List<ProjectExecutorStepDTO> executorSteps = new ArrayList<>(steps.size());

        // ????????????
        for (StepMetaDataCombi stepMetaDataCombi : steps) {
            final StepInterface step = stepMetaDataCombi.step;

            // ????????????????????????
            StepStatus stepStatus = new StepStatus(step);
            // ??????????????????
            final ProjectExecutorStepDTO executorStep = ProjectExecutorStepDTO.builder()
                .stepName(step.getStepname())
                .linesInput(stepStatus.getLinesInput())
                .linesOutput(stepStatus.getLinesOutput())
                .linesRead(stepStatus.getLinesRead())
                .linesOutput(stepStatus.getLinesOutput())
                .linesUpdated(stepStatus.getLinesUpdated())
                .linesRejected(stepStatus.getLinesRejected())
                .stepExecutionStatus(step.getStatus().name())
                .statusDescription(stepStatus.getStatusDescription())
                .seconds(stepStatus.getSeconds())
                .speed(stepStatus.getSpeed())
                .errors(stepStatus.getErrors()).build();
            executorSteps.add(executorStep);
        }

        return executorSteps;
    }

    /**
     * ?????????????????????id
     *
     * @return ???????????????id
     */
    public String generateExecutorId() {
        // uuid + 5???????????????
        return UUIDUtils.generateUUID32() + RandomStringUtils.randomAlphabetic(5);
    }

    /**
     * ????????????
     *
     * @param executorId      ?????????id
     * @param projectFile     ????????????
     * @param executorParamVO ????????????
     * @return TransExecutor
     */
    private TransExecutor start(String executorId, String projectFile, ProjectExecutorParam executorParamVO) throws Exception {
        // ??????TransMeta ??????
        TransMeta transMeta = buildTransMeta(executorId, projectFile);

        TransExecutionConfiguration executionConfiguration = new TransExecutionConfiguration();
        // ???????????????????????????????????????????????????
        executionConfiguration.setExecutingLocally(true);
        executionConfiguration.setExecutingRemotely(false);
        executionConfiguration.setExecutingClustered(false);
        // ????????????
        LogLevel logLevel = LogLevel.getLogLevelForCode(executorParamVO.getLogLevel());

        // ?????????????????????
        executionConfiguration.setSafeModeEnabled(executorParamVO.isSafeModeEnabled());
        executionConfiguration.getUsedVariables(transMeta);
        executionConfiguration.setLogLevel(logLevel);

        // ??????????????????????????????
        executionConfiguration.setRunConfiguration(engineName);


        // ???????????????
        TransExecutor transExecutor = new TransExecutor(transMeta);
        //??????????????????
        executionConfiguration.setVariables(executorParamVO.getVariables());

        try {
            //????????????
            transExecutor.start(executionConfiguration, executorParamVO);

        } catch (Exception e) {
            logger.error("??????????????????", e);
            // ??????????????????
            throw new DpException(StatusCode.CODE_10010.getCode()
                , new LogBrowser(transExecutor).getRealTimeLog());
        }

        return transExecutor;
    }

    /**
     * ??????TransMeta ??????
     *
     * @param projectFile ????????????
     * @return TransMeta
     */
    private TransMeta buildTransMeta(String executorId, String projectFile) throws IOException, KettleXMLException, KettleMissingPluginsException {
        Document document;
        //json???xml
        if (!projectFile.startsWith("<?xml")) {
            // json???xml
            projectFile = StringEscapeUtils.unescapeXml(projectFile);
            projectFile = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" + XML.toString(new JSONLinkedObject(projectFile));

            logger.debug("json?????????xml???????????????xml:{}", projectFile);
        }

        // ??????????????????
        File outFile = new File(tmpFolder, executorId + tmpFileSuffix);
        FileUtils.writeStringToFile(outFile, projectFile);

        // ??????xml
        document = XMLHandler.loadXMLString(projectFile);

        TransMeta transMeta = new TransMeta();
        transMeta.loadXML(
            document.getDocumentElement(), outFile.getPath(), null, null, true, new Variables(),
            (message, rememberText, rememberPropertyName) -> {
                // Yes means: overwrite
                return true;
            });

        if (transMeta.hasMissingPlugins()) {
            logger.info("???{}????????????????????????", projectFile);
        }

        return transMeta;
    }

}
