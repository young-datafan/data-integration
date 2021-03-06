package com.youngdatafan.di.run.management.server.service;

import com.youngdatafan.dataintegration.core.exception.ValidationException;
import com.youngdatafan.dataintegration.core.model.Result;
import com.youngdatafan.dataintegration.core.util.JsonUtils;
import com.youngdatafan.dataintegration.core.util.StatusCode;
import com.youngdatafan.dataintegration.core.util.UUIDUtils;
import com.youngdatafan.dataintegration.core.util.json.JSONLinkedObject;
import com.youngdatafan.dataintegration.core.util.json.XML;
import com.youngdatafan.di.run.management.server.bean.ProjectExecutor;
import com.youngdatafan.di.run.management.server.dto.ProjectExecutorDTO;
import com.youngdatafan.di.run.management.server.dto.ProjectExecutorStepDTO;
import com.youngdatafan.di.run.management.server.dto.ProjectHistoryExecuteDTO;
import com.youngdatafan.di.run.management.server.entity.DpDeProjectExecHistory;
import com.youngdatafan.di.run.management.server.mapper.DpDeProjectExecHistoryMapper;
import com.youngdatafan.di.run.management.server.trans.LogBrowser;
import com.youngdatafan.di.run.management.server.trans.TransExecutor;
import com.youngdatafan.di.run.management.server.trans.TransPreview;
import com.youngdatafan.di.run.management.server.util.ProjectExecuteEnv;
import com.youngdatafan.di.run.management.server.util.ProjectExecuteStatus;
import com.youngdatafan.di.run.management.server.vo.ProjectExecutorParam;
import com.youngdatafan.di.run.management.server.vo.ProjectHistoryExecuteVO;
import com.youngdatafan.di.run.management.server.vo.ProjectStopVO;
import com.youngdatafan.di.run.management.server.websocket.ProjectExecuteCallback;
import com.youngdatafan.di.run.management.util.ProjectExecuteException;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.pentaho.di.trans.step.BaseStepData;
import org.pentaho.di.trans.step.StepInterface;
import org.pentaho.di.trans.step.StepMetaDataCombi;
import org.pentaho.di.trans.step.StepStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;

/**
 * ????????????????????????
 *
 * @author gavin
 * @since 2020/2/13 10:53 ??????
 */
@Service
public class ProjectExecutorService {

    public static final String CACHE_PREFIX = "di_executor_";
    public static final String INSTANCEID = "instanceId";
    private static final Logger logger = LoggerFactory.getLogger(ProjectExecutorService.class);
    /**
     * ????????????????????????map
     */
    private final ConcurrentHashMap<String, ProjectExecutor> executorMap = new ConcurrentHashMap<>();
    private final RedisTemplate<String, String> redisTemplate;
    private final DpDeProjectExecHistoryMapper projectExecHistoryMapper;


    @Value("${spring.cloud.consul.discovery.instance-id}")
    private String instanceId;
    /**
     * ??????????????????????????????
     */
    @Value("${dp.project.execute.refreshRateMs:800}")
    private int refreshRateMs;
    /**
     * ???????????????
     */
    @Value("${dp.project.execute.tmpFolder:./tmp/}")
    private String tmpFolder;
    /**
     * ??????????????????
     */
    @Value("${dp.project.execute.tmpFileSuffix:.xml}")
    private String tmpFileSuffix;
    @Value("${dp.project.execute.deleteTmpFile:true}")
    private boolean deleteTmpFile;
    @Value("${kettle.engine.name:Pentaho local}")
    private String engineName;

    @Autowired
    public ProjectExecutorService(RedisTemplate<String, String> redisTemplate, DpDeProjectExecHistoryMapper projectExecHistoryMapper) {
        this.redisTemplate = redisTemplate;
        this.projectExecHistoryMapper = projectExecHistoryMapper;
    }

    /**
     * ???????????????????????????
     *
     * @param executorId ?????????id
     * @return ?????????????????????????????????
     */
    public boolean pauseResume(String executorId) {
        //TODO ????????????????????????????????????
        ProjectExecutor projectExecutor = executorMap.get(executorId);
        if (projectExecutor != null) {
            TransExecutor transExecutor = projectExecutor.getTransExecutor();
            transExecutor.pauseResume();

            boolean pausing = transExecutor.isPausing();
            logger.info("???????????????????????????????????????ID:{},isPausing:{}", executorId, pausing);

            return pausing;
        }

        return false;
    }

    /**
     * ???????????????
     *
     * @param executorId ?????????id
     */
    public boolean stop(String executorId) {
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
     * @param userId    ??????id
     * @param projectId ??????id
     */
    public void checkExists(String userId, String projectId) {
        final String cacheKey = CACHE_PREFIX + userId;
        if (redisTemplate.opsForHash().hasKey(cacheKey, projectId)) {
            throw new ValidationException(StatusCode.CODE_10010, "?????????????????????");
        }
    }

    /**
     * ????????????????????????
     *
     * @param userId        ??????id
     * @param projectStopVO ProjectStopVO
     */
    public boolean stop(String userId, ProjectStopVO projectStopVO) {
        final String cacheKey = CACHE_PREFIX + userId;
        redisTemplate.opsForHash().delete(cacheKey, projectStopVO.getProjectId());

        // ????????????
        return stop(projectStopVO.getExecutorId());
    }

    /**
     * ????????????
     *
     * @param executorParamVO ????????????
     * @param executeCallback ??????????????????
     */
    @Async("asyncTaskExecutor")
    public void asyncExecuteByFile(String projectFile, ProjectExecutorParam executorParamVO
            , ProjectExecuteCallback executeCallback) {
        // ??????
        executeByFile(projectFile, executorParamVO, executeCallback);
    }

    /**
     * ????????????????????????
     *
     * @param executorParamVO ????????????
     * @param executeCallback ??????????????????
     */
    public void executeByFile(String projectFile, ProjectExecutorParam executorParamVO
            , ProjectExecuteCallback executeCallback) {
        executorParamVO.setStartTime(new Date());
        final String executorId = executorParamVO.getExecutorId();

        // ?????????????????????????????????
        final String cacheKey = saveUserExecuteProject(executorParamVO);

        final long currentTimeMillis = System.currentTimeMillis();
        // ??????????????????
        final DpDeProjectExecHistory dpDeProjectExecHistory = saveExecuteHistory(executorParamVO);
        TransExecutor executor = null;

        try {
            // ??????
            executor = execute(executorId, projectFile, executorParamVO, executeCallback);

        } catch (ProjectExecuteException e) {
            // ??????????????????
            final TransExecutor transExecutor = e.getTransExecutor();
            final List<ProjectExecutorStepDTO> stepDTOS = buildStepStatus(transExecutor);
            responseError(executorParamVO, executeCallback, executorId
                    , new LogBrowser(transExecutor).getRealTimeLog()
                    , stepDTOS);

        } catch (Exception e) {
            logger.error("??????????????????", e);
            responseError(executorParamVO, executeCallback, executorId, "?????????????????????\n" + e.getMessage(), null);

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
            redisTemplate.opsForHash().delete(cacheKey, executorParamVO.getProjectId());

            // ????????????????????????
            updateHistoryStatus(currentTimeMillis, dpDeProjectExecHistory, executor);
        }
    }

    private void responseError(ProjectExecutorParam executorParamVO, ProjectExecuteCallback executeCallback, String executorId
            , String log, List<ProjectExecutorStepDTO> stepDTOS) {
        // ??????????????????
        Result webSocketResponse = Result.fail(StatusCode.CODE_10010.getCode()
                , ProjectExecutorDTO.builder()
                        .requestId(executorParamVO.getRequestId())
                        .executorId(executorId)
                        .transFinished(true)
                        .executorSteps(stepDTOS)
                        // ??????????????????
                        .log(log).build(), "");

        // ????????????
        executeCallback.onMessage("/runningState", webSocketResponse);

        // ??????map???????????????????????????
        ProjectExecutor projectExecutor = executorMap.remove(executorId);
        if (projectExecutor != null && projectExecutor.getTransExecutor() != null) {
            projectExecutor.getTransExecutor().stop();
        }
    }

    private String saveUserExecuteProject(ProjectExecutorParam executorParamVO) {
        final String userId = executorParamVO.getUserId();
        final String cacheKey = CACHE_PREFIX + userId;

        // ???????????????????????????
        final HashMap<Object, Object> executeProjectCacheMap = new HashMap<>(2);
        executeProjectCacheMap.put(executorParamVO.getProjectId(), JsonUtils.toString(executorParamVO));
        executeProjectCacheMap.put(INSTANCEID, instanceId);
        redisTemplate.boundHashOps(cacheKey).putAll(executeProjectCacheMap);

        // ????????????
        redisTemplate.expire(cacheKey, 1, TimeUnit.DAYS);

        return cacheKey;
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
        dpDeProjectExecHistory.setExecEnv(ProjectExecuteEnv.JCPT.name());
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
    private TransExecutor execute(String executorId, String projectFile, ProjectExecutorParam executorParamVO
            , ProjectExecuteCallback executeCallback) throws Exception {
        // ????????????
        TransExecutor transExecutor = start(executorId, projectFile, executorParamVO);

        ProjectExecutor projectExecutor = new ProjectExecutor(transExecutor, null);
        // ???TransExecutor?????????map?????????
        executorMap.put(executorId, projectExecutor);

        // ???????????????
        LogBrowser logBrowser = new LogBrowser(transExecutor);

        // ????????????????????????
        while (!transExecutor.isFinishedOrStopped()) {

            // ???????????????????????????????????????
            List<ProjectExecutorStepDTO> executorSteps = buildStepStatus(transExecutor);
            // ??????????????????
            Result<ProjectExecutorDTO, Object> webSocketResponse = Result.success(ProjectExecutorDTO.builder()
                    .executorId(executorId)
                    .requestId(executorParamVO.getRequestId())
                    .executorSteps(executorSteps)
                    .errors(transExecutor.getTrans().getErrors())
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
        }

        //????????????????????????
        final List<ProjectExecutorStepDTO> executorSteps = buildStepStatus(transExecutor);
        // ??????????????????
        Result<ProjectExecutorDTO, Object> webSocketResponse = Result.success(
                ProjectExecutorDTO.builder()
                        .executorId(executorId)
                        .requestId(executorParamVO.getRequestId())
                        .executorSteps(executorSteps)
                        .errors(transExecutor.getTrans().getErrors())
                        .transFinished(true)
                        // ??????????????????
                        .log(logBrowser.getRealTimeLog()).build());
        // ????????????
        executeCallback.onMessage("/runningState", webSocketResponse);

        logger.info("??????????????????????????????id:{}", executorId);

        return transExecutor;
    }

    /**
     * ????????????????????????
     *
     * @param transExecutor TransExecutor
     * @return ArrayList
     */
    private List<ProjectExecutorStepDTO> buildStepStatus(TransExecutor transExecutor) {
        if (transExecutor == null || transExecutor.getTrans() == null) {
            return null;
        }

        final List<StepMetaDataCombi> steps = transExecutor.getTrans().getSteps();
        final TransPreview transPreview = transExecutor.getTransPreview();

        List<ProjectExecutorStepDTO> executorSteps = new ArrayList<>(steps.size());

        // ????????????
        for (StepMetaDataCombi stepMetaDataCombi : steps) {
            final StepInterface step = stepMetaDataCombi.step;

            // ????????????????????????
            StepStatus stepStatus = new StepStatus(step);
            // ??????????????????
            final String stepname = step.getStepname();

            // ??????????????????
            List<String[]> preViewData = null;
            List<String> previewFieldNames = null;
            if (step.getStatus() == BaseStepData.StepExecutionStatus.STATUS_FINISHED) {
                preViewData = transPreview.getData(stepname);
                previewFieldNames = transPreview.getFieldNames(stepname);
                // ?????????????????????
                transPreview.remove(stepname);
            }

            final ProjectExecutorStepDTO executorStep = ProjectExecutorStepDTO.builder()
                    .stepName(stepname)
                    .copy(stepStatus.getCopy())
                    .priority(stepStatus.getPriority())
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
                    .previewRows(preViewData)
                    .previewFieldNames(previewFieldNames)
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
    public String generateExecutorId(String projectId) {
        // projectId + 12???????????????
        return projectId + "_" + RandomStringUtils.randomAlphabetic(12);
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

        //??????????????????
        executionConfiguration.setVariables(executorParamVO.getVariables());

        // ???????????????
        TransExecutor transExecutor = new TransExecutor(transMeta);

        try {
            //????????????
            transExecutor.start(executionConfiguration,executorParamVO);

        } catch (Exception e) {
            logger.error("??????????????????", e);
            // ??????????????????
            throw new ProjectExecuteException(transExecutor);
        }

        return transExecutor;
    }

    /**
     * ??????TransMeta ??????
     *
     * @param projectFile ????????????
     * @return TransMeta
     */
    public TransMeta buildTransMeta(String executorId, String projectFile) throws IOException, KettleXMLException, KettleMissingPluginsException {
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

    public boolean executorIsExists(String executorId) {
        return executorMap.containsKey(executorId);
    }

    /**
     * ???????????????????????????
     *
     * @param userId ??????id
     * @return list
     */
    public List<ProjectHistoryExecuteDTO> selectRunningProject(String userId) {
        final String cacheKey = CACHE_PREFIX + userId;

        final Map<Object, Object> entries = redisTemplate.opsForHash().entries(cacheKey);
        entries.remove(INSTANCEID);

        List<ProjectHistoryExecuteDTO> result = new ArrayList<>();
        for (Object value : entries.values()) {
            try {
                final ProjectExecutorParam projectExecutorParam = JsonUtils.parseObject((String) value, ProjectExecutorParam.class);
                final ProjectHistoryExecuteDTO projectHistoryExecuteDTO = new ProjectHistoryExecuteDTO();
                BeanUtils.copyProperties(projectExecutorParam, projectHistoryExecuteDTO);
                result.add(projectHistoryExecuteDTO);
            } catch (IOException e) {
                logger.error("???????????????json: {}", value);
            }
        }

        // ????????????
        result.sort((o1, o2) -> o2.getStartTime().compareTo(o1.getStartTime()));

        return result;
    }

    /**
     * ??????????????????
     *
     * @param projectHistoryExecuteVO ProjectHistoryExecuteVO
     * @return list
     */
    public List<ProjectHistoryExecuteDTO> selectUserHistoryExecute(String userId, ProjectHistoryExecuteVO projectHistoryExecuteVO) {
        return projectExecHistoryMapper.selectUserHistoryExecute(userId, projectHistoryExecuteVO.getProjectName()
                , projectHistoryExecuteVO.getStartTime(), projectHistoryExecuteVO.getEndTime());
    }

}
