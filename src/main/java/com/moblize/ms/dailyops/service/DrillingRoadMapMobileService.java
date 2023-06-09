package com.moblize.ms.dailyops.service;

import com.moblize.ms.dailyops.client.AlarmDetailClient;
import com.moblize.ms.dailyops.client.KpiDashboardClient;
import com.moblize.ms.dailyops.client.WitsmlLogsClient;
import com.moblize.ms.dailyops.dao.WellFormationDAO;
import com.moblize.ms.dailyops.domain.DrillingProfileData;
import com.moblize.ms.dailyops.domain.FormationMarker;
import com.moblize.ms.dailyops.domain.MongoWell;
import com.moblize.ms.dailyops.domain.mongo.DepthLogResponse;
import com.moblize.ms.dailyops.domain.mongo.MongoLog;
import com.moblize.ms.dailyops.dto.DrillingRoadMapSearchDTO;
import com.moblize.ms.dailyops.dto.DrillingRoadMapWells;
import com.moblize.ms.dailyops.dto.DrillingRoadmapJsonResponse;
import com.moblize.ms.dailyops.dto.DrillingRoadmapJsonResponse.AverageData;
import com.moblize.ms.dailyops.dto.LogResponse;
import com.moblize.ms.dailyops.repository.DrillingProfileDataRepository;
import com.moblize.ms.dailyops.repository.mongo.mob.MongoWellRepository;
import com.moblize.ms.dailyops.service.dto.HoleSection;
import com.moblize.ms.dailyops.service.dto.MudProperties;
import com.moblize.ms.dailyops.service.dto.SurveyRecord;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.text.DecimalFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Slf4j
@Service
public class DrillingRoadMapMobileService {

    @Value("${rest.nodedrilling.url}")
    private String nodeDrillingURL;
    @Value("${rest.nodedrilling.user}")
    private String nextgenUsername;
    @Value("${rest.nodedrilling.pwd}")
    private String nextgenPassword;
    @Value("${rest.consumer-api.url}")
    private String consumerUri;
    @Value("${rest.consumer-api.user}")
    private String consumerUsername;
    @Value("${rest.consumer-api.pwd}")
    private String consumerPwd;
    private static final String DEFAULT_WELLBORE_ID = "Wellbore1";
    @Autowired
    private MongoWellRepository mongoWellRepository;
    @Autowired
    private BCWDepthLogPlotService bcwDepthLogPlotService;
    @Autowired
    private WitsmlLogsClient witsmlLogsClient;
    @Autowired
    private KpiDashboardClient kpiDashboardClient;
    @Autowired
    private AlarmDetailClient alarmDetailClient;
    @Autowired
    private DrillingProfileDataRepository drillingProfileDataRepository;
    @Autowired
    private DrillingRoadMapFormationBuilder drillingRoadMapFormationBuilder;
    @Autowired
    private WellFormationDAO wellFormationDAO;

    Map<String, List<FormationMarker>> formationMarkerMap = new LinkedHashMap<>();

    static RestTemplate restTemplate = new RestTemplate();
    public DrillingRoadmapJsonResponse readMobile(DrillingRoadMapSearchDTO drillingRoadMapSearchDTO) {
        long startTime = System.currentTimeMillis();
        DrillingRoadmapJsonResponse drillingRoadmapJsonResponses = new DrillingRoadmapJsonResponse();
        try {
            drillingRoadmapJsonResponses = getDrillingRoadmapResponses(drillingRoadMapSearchDTO);
        } catch (Exception e) {
            log.error("Error occurred while serving Drilling Drag Average data API", e);
            throw new RuntimeException("Error occurred while serving Drilling Drag Average data API", e);
        }
        log.info("Drilling RoadMap mobile data API for well:{} served in time: {}.",drillingRoadMapSearchDTO.getPrimaryWellUid(), System.currentTimeMillis() - startTime);
        return drillingRoadmapJsonResponses;
    }

    public DrillingRoadmapJsonResponse getDrillingRoadmapResponses(DrillingRoadMapSearchDTO requestDTO) {

        String currentMeasuredDepth = "";
        String currenttvd = "";
        String currentInclination = "";
        String currentAzimuth = "";
        String currentWellMudWeight = "";
        String paceSetterFormationWellUid = "";
        String paceSetterFormationWellName = "";
        String paceSetterWellboreUid = "";
        String currentSection = "";
        String currentRigState = "";
        String currentWellFormation = "";
        DrillingRoadmapJsonResponse response = new DrillingRoadmapJsonResponse();

        try {
            CompletableFuture<String> measuredDepthLogFuture = getMeasuredDepth(requestDTO);
            CompletableFuture<String> currentWellMudWeightFuture = getWellMudWeight(requestDTO.getPrimaryWellUid());
            CompletableFuture<List<DrillingRoadMapWells>> bcwFormationDataFuture = getBcwFormationData(requestDTO);
            CompletableFuture<String> statusFuture = getStatus(requestDTO.getPrimaryWellUid());
            CompletableFuture<String> daysVsAFEFuture = getDaysVsAFE(requestDTO);

            SurveyRecord surveyRecord = alarmDetailClient.getLastSurveyData(requestDTO.getPrimaryWellUid(), statusFuture.get());
            if (surveyRecord != null) {
                currenttvd = surveyRecord.getTvd() != null ? surveyRecord.getTvd().toString() : "";
                currentInclination = surveyRecord.getIncl() != null ? surveyRecord.getIncl().toString() : "";
                currentAzimuth = surveyRecord.getAzimuth() != null ? surveyRecord.getAzimuth().toString() : "";
            }

            // to calculate primary well data and average data.
            processBcwData(response, requestDTO.getPrimaryWellUid());

            // to Calculate paceSetterFormation data.
            DrillingRoadMapWells currrentWellBcwFormationMap = response.getPrimaryWellDrillingRoadMap() == null ? null : getDrillingRoadMapWells(response);
            response.setFormationBcwData(bcwFormationDataFuture.get());
            if (currrentWellBcwFormationMap != null) {
                currentWellFormation = currrentWellBcwFormationMap.getFormationName();
                Optional<DrillingRoadMapWells> paceSetterFormation = response.getFormationBcwData().stream().filter(formation ->
                    formation.getFormationName().equalsIgnoreCase(currrentWellBcwFormationMap.getFormationName())
                ).findFirst();
                if (paceSetterFormation.isPresent()) {
                    paceSetterFormationWellUid = paceSetterFormation.get().getWellUid();
                    MongoWell mongoWell = mongoWellRepository.findFirstByUid(paceSetterFormationWellUid);
                    if(null!=mongoWell.getName()) {
                        paceSetterFormationWellName = mongoWell.getName();
                    }
                    paceSetterWellboreUid = DEFAULT_WELLBORE_ID;
                    response.setPaceSetterFormationMap(paceSetterFormation.get());
                }
            }
            currentMeasuredDepth = measuredDepthLogFuture.get();
            currentWellMudWeight = currentWellMudWeightFuture.get();

            // to get the current rig state and section.
            CompletableFuture<String> currentSectionFuture = getCurrentSection(requestDTO, currentMeasuredDepth);
            CompletableFuture<String> rigStateFuture = getRigState(requestDTO, currentMeasuredDepth);
            CompletableFuture.allOf(currentSectionFuture,rigStateFuture).join();
            currentRigState = rigStateFuture.get();
            currentSection = currentSectionFuture.get();

            response.setCurrentMeasuredDepth(currentMeasuredDepth);
            response.setCurrenttvd(currenttvd);
            response.setCurrentInclination(currentInclination);
            response.setCurrentAzimuth(currentAzimuth);
            response.setCurrentWellDepth(currentMeasuredDepth);
            response.setCurrentWellEndIndex(currentMeasuredDepth);
            response.setPaceSetterWellUid(paceSetterFormationWellUid);
            response.setPaceSetterWellName(paceSetterFormationWellName);
            response.setPaceSetterWellboreUid(paceSetterWellboreUid);
            response.setCurrentWellFormation(currentWellFormation);
            response.setCurrentSection(currentSection);
            response.setCurrentWellMudWeight(currentWellMudWeight);
            response.setCurrentRigState(currentRigState);
            response.setDaysVsAEF(daysVsAFEFuture.get());
            response.setCurrrentWellBcwFormationMap(currrentWellBcwFormationMap);
            if(response.getCurrrentWellBcwFormationMap()==null){
                response.setAverageData(Collections.emptyList());
            }
        } catch (Exception exception) {
            log.error("Error while processing the DrillingRoadMapPayLoad [process] for the payload :" + requestDTO.toString(), exception);
        }
        return response;
    }

    @Async
    private CompletableFuture<String> getStatus(String wellUid){
        MongoWell mongoWell = mongoWellRepository.findByUid(wellUid);
        if(null!=mongoWell) {
            return CompletableFuture.completedFuture(mongoWell.getStatusWell());
        }else {
            return CompletableFuture.completedFuture("null");
        }
    }

    @Async
    private CompletableFuture<List<DrillingRoadMapWells>> getBcwFormationData(DrillingRoadMapSearchDTO searchDTO) {
        formationMarkerMap.putAll(drillingRoadMapFormationBuilder.getFormationMap(searchDTO.getPrimaryWellUid(), searchDTO.getOffsetWellUids(), "Wellbore1"));
        List<String> primaryWellFormation = formationMarkerMap.get(searchDTO.getPrimaryWellUid()).stream().map(formation -> formation.getName()).collect(Collectors.toList());

        Set<String> formationWellList = formationMarkerMap.keySet();
        formationWellList.remove(searchDTO.getPrimaryWellUid());
        long startTime = System.currentTimeMillis();
        List<DrillingRoadMapWells> bcwFormationData = primaryWellFormation.isEmpty() || formationWellList.isEmpty() ? Collections.emptyList() : wellFormationDAO.getBCWDataFormation(new ArrayList<>(formationWellList), primaryWellFormation);
        log.info("Query: BcwFormationData calculated for wells took : {}s, size: {}", System.currentTimeMillis() - startTime, bcwFormationData.size());
        return CompletableFuture.completedFuture(bcwFormationData);
    }

    private void processBcwData(DrillingRoadmapJsonResponse response, String primaryWellUid) {
        long startTime = System.currentTimeMillis();
        Set<String> formationWellList = new HashSet<>();
        formationWellList.addAll(formationMarkerMap.keySet());
        formationWellList.add(primaryWellUid);

        //DB call to get BCW data
        List<DrillingRoadMapWells> bcwData = wellFormationDAO.getBcwData(new ArrayList<>(formationWellList));
        response.setBcwData(bcwData);

        //Calculating primary well data
        List<DrillingRoadMapWells> primaryRoadMapWells = bcwData.stream()
            .filter(well->well.getWellUid().equalsIgnoreCase(primaryWellUid)).collect(Collectors.toList());
        response.setPrimaryWellDrillingRoadMap(primaryRoadMapWells);

        //To calculate average data
        formationMarkerMap.remove(primaryWellUid);
        Set<String> formationList = new LinkedHashSet<>();
        for( String well:formationMarkerMap.keySet()){
            formationList.addAll(formationMarkerMap.get(well).stream().map(FormationMarker::getName).collect(Collectors.toSet()));
        }
        List<DrillingRoadMapWells> drillingRoadMapWells = bcwData.stream()
            .filter(well->formationList.contains(well.getFormationName()) && !well.getWellUid().equalsIgnoreCase(primaryWellUid))
            .collect(Collectors.toList());
        List<AverageData> averageData = calculateAverageData(formationList, drillingRoadMapWells);
        response.setAverageData(averageData);
        log.info("Primary, Average data calculated for wells took : {}s, size: {}", System.currentTimeMillis() - startTime, drillingRoadMapWells.size());
    }

    private List<AverageData> calculateAverageData(Set<String> formationList, List<DrillingRoadMapWells> roadMapWells) {
        return formationList.parallelStream().map(formation -> {
            AverageData drillingRoadMapWell = new AverageData();
            float mudFlowInAvg = 0f;
            float surfaceTorqueMax = 0f;
            float pumpPress = 0f;
            float weightonBitMax = 0f;
            float ROPAvg = 0f;
            float RPMA = 0f;
            float diffPressure = 0f;
            int count = 0;
            List<DrillingRoadMapWells> filteredData = roadMapWells.parallelStream().filter(data -> data.getFormationName().equalsIgnoreCase(formation)).collect(Collectors.toList());
            for (DrillingRoadMapWells data : filteredData) {
                mudFlowInAvg += Float.parseFloat(data.getMudFlowInAvg());
                surfaceTorqueMax += Float.parseFloat(data.getSurfaceTorqueMax());
                pumpPress += Float.parseFloat(data.getPumpPress());
                weightonBitMax += Float.parseFloat(data.getWeightonBitMax());
                ROPAvg += Float.parseFloat(data.getROPAvg());
                RPMA += Float.parseFloat(data.getRPMA());
                diffPressure += Float.parseFloat(data.getDiffPressure());
                count++;
            }
            drillingRoadMapWell.setFormationName(formation);
            drillingRoadMapWell.setMudFlowInAvg(String.valueOf(Math.round(mudFlowInAvg / count)));
            drillingRoadMapWell.setSurfaceTorqueMax(String.valueOf(Math.round(surfaceTorqueMax / count)));
            drillingRoadMapWell.setPumpPress(String.valueOf(Math.round(pumpPress / count)));
            drillingRoadMapWell.setWeightonBitMax(String.valueOf(Math.round(weightonBitMax / count)));
            drillingRoadMapWell.setROPAvg(String.valueOf(Math.round(ROPAvg / count)));
            drillingRoadMapWell.setRPMA(String.valueOf(Math.round(RPMA / count)));
            drillingRoadMapWell.setDiffPressure(String.valueOf(Math.round(diffPressure / count)));
            return drillingRoadMapWell;
        }).collect(Collectors.toList());
    }

    private DrillingRoadMapWells getDrillingRoadMapWells(DrillingRoadmapJsonResponse drillingRoadmapJsonResponses) {
        List<DrillingRoadMapWells> primaryWellDrillingRoadMap = new ArrayList<>(drillingRoadmapJsonResponses.getPrimaryWellDrillingRoadMap());
        primaryWellDrillingRoadMap.sort(new Comparator<DrillingRoadMapWells>() {
            @Override
            public int compare(DrillingRoadMapWells drillingRoadMapWells1, DrillingRoadMapWells drillingRoadMapWells2) {
                int md1 = Integer.parseInt(drillingRoadMapWells1.getMD());
                int md2 = Integer.parseInt(drillingRoadMapWells2.getMD());
                if (md1 == md2) {
                    return 0;
                }
                return md1 < md2 ? -1 : 1;
            }
        });
        return primaryWellDrillingRoadMap != null && !primaryWellDrillingRoadMap.isEmpty() ? primaryWellDrillingRoadMap.get(primaryWellDrillingRoadMap.size() - 1) : null;
    }

    @Async
    private CompletableFuture<String> getMeasuredDepth(DrillingRoadMapSearchDTO drillingRoadMapSearchDTO) {
        MongoLog logs = witsmlLogsClient.getDepthLog(drillingRoadMapSearchDTO.getPrimaryWellUid());
        if(logs!=null){
            return CompletableFuture.completedFuture(logs.getEndIndex().toString());
        }
        return CompletableFuture.completedFuture("");
    }
    @Async
    private CompletableFuture<String> getCurrentSection(DrillingRoadMapSearchDTO drillingRoadMapSearchDTO, String currentMeasuredDepth) {
        String currentSection;
        List<HoleSection> sections = kpiDashboardClient.getHoleSections(drillingRoadMapSearchDTO.getPrimaryWellUid());
        Float measuredDepth = 0f;
        if(!currentMeasuredDepth.equals("")){
            measuredDepth = Float.parseFloat(currentMeasuredDepth);
        }
        final Float finalCurrentMeasuredDepth = measuredDepth;
        Optional<HoleSection> holesection = Optional.empty();
        if(sections!=null && !sections.isEmpty()) {
            holesection = sections.stream().filter(section -> finalCurrentMeasuredDepth > section.getFromDepth() && finalCurrentMeasuredDepth <= section.getToDepth()).findFirst();
        }
        currentSection = holesection.isPresent() ? holesection.get().getSection().name() : "";
        return CompletableFuture.completedFuture(currentSection);
    }

    @Async
    private CompletableFuture<List<DrillingProfileData>> getDrillingProfileData(String wellUid) {
        return CompletableFuture.completedFuture(drillingProfileDataRepository.findByWellUid((wellUid)));
    }

    @Async
    private CompletableFuture<String> getDaysVsAFE(DrillingRoadMapSearchDTO drillingRoadMapSearchDTO) {
        String daysVsAFE = "";
        DecimalFormat decimalFormat = new DecimalFormat("#.00");
        List<DrillingProfileData> drillingProfileDataList = null;
        List<Map<String, Object>> dayVsDepthList = null;
        try {
            CompletableFuture<List<DrillingProfileData>> drillingProfileDataFuture = getDrillingProfileData(drillingRoadMapSearchDTO.getPrimaryWellUid());
            CompletableFuture<List<Map<String, Object>>> dayVDepthListFuture = getDayVDepthList(drillingRoadMapSearchDTO.getPrimaryWellUid());
            CompletableFuture.allOf(drillingProfileDataFuture,dayVDepthListFuture).join();
            drillingProfileDataList = drillingProfileDataFuture.get();
            dayVsDepthList= dayVDepthListFuture.get();
        } catch (Exception e){
            log.error("Error while calculating daysVsAFE data for wells",e);
        }
        if (null!=dayVsDepthList && !dayVsDepthList.isEmpty()) {
            Map<String, Object> firstDvdPoint = dayVsDepthList.isEmpty() ? null : dayVsDepthList.get(0);
            Map<String, Object> lastDvdPoint = dayVsDepthList.isEmpty() ? null : dayVsDepthList.get(dayVsDepthList.size()-1);
            float days = ((lastDvdPoint != null ? Float.valueOf(lastDvdPoint.getOrDefault("days", 0f).toString()) : 0f)
                - (firstDvdPoint != null ? Float.valueOf(firstDvdPoint.getOrDefault("days", 0f).toString()) : 0f))
                / (24 * 60 * 60 * 1000);
            float depth = lastDvdPoint != null ? Float.valueOf(lastDvdPoint.getOrDefault("depth", 0).toString()) : 0f;

            Float startDepth = firstDvdPoint != null ? Float.valueOf(firstDvdPoint.getOrDefault("depth", 0f).toString()) : 0;
            float drillingProfileDays = 0.0f;


            if (null!=drillingProfileDataList && !drillingProfileDataList.isEmpty()) {
                float daysAFE = drillingProfileDataList.get(drillingProfileDataList.size() - 1).getDays();
                if (daysAFE < days) {
                    drillingProfileDays = daysAFE;
                } else {

                    Optional<DrillingProfileData> drillingProfileEndPoint = drillingProfileDataList.stream().filter(drillingProfile -> drillingProfile.getHoleDepth() > depth).findFirst();

                    if (drillingProfileEndPoint.isPresent()) {

                        DrillingProfileData drillingProfileStartPoint = drillingProfileDataList.get(drillingProfileDataList.indexOf(drillingProfileEndPoint.get()) - 1);
                        if (drillingProfileStartPoint != null) {
                            float m = (drillingProfileEndPoint.get().getHoleDepth() - drillingProfileStartPoint.getHoleDepth()) / (drillingProfileEndPoint.get().getDays() - drillingProfileStartPoint.getDays());

                            drillingProfileDays = (depth - drillingProfileStartPoint.getHoleDepth() + (m * drillingProfileStartPoint.getDays())) / m;
                        }
                    } else {
                        drillingProfileDays = drillingProfileDataList.get(drillingProfileDataList.size() - 1).getDays();
                    }
                }
                if (drillingProfileDays > days) {
                    daysVsAFE = decimalFormat.format(drillingProfileDays - days) + " Days Ahead";
                } else {
                    daysVsAFE = decimalFormat.format(days - drillingProfileDays) + " Days Behind";
                }
            } else{
                daysVsAFE = "No AFE Data";
            }

        } else {
            daysVsAFE = "";
        }

        return CompletableFuture.completedFuture(daysVsAFE);
    }

    @Async
    public CompletableFuture<List<Map<String, Object>>> getDayVDepthList(String wellUid) {
        Object dayVDepthLog = witsmlLogsClient.getDayVDepthLog(wellUid, DEFAULT_WELLBORE_ID, null, null, null, null, null, null, null);
        return CompletableFuture.completedFuture((List<Map<String, Object>>) dayVDepthLog);
    }

    @Async
    public CompletableFuture<String> getRigState(DrillingRoadMapSearchDTO drillingRoadMapSearchDTO, String currentMeasuredDepth) {
        LogResponse data = null;
        if (currentMeasuredDepth.equals("")) {
            currentMeasuredDepth = "0";
        }
        try {
            if (drillingRoadMapSearchDTO != null) {
                String url = nodeDrillingURL + "log?wellUid=" + drillingRoadMapSearchDTO.getPrimaryWellUid() + "&type=depth&startIndex="
                    + (Double.parseDouble(currentMeasuredDepth) - 50) + "&endIndex=" + currentMeasuredDepth + "&needToConvertRange=true";
                data = restTemplate.exchange(url, HttpMethod.GET, createHeaders(nextgenUsername, nextgenPassword), new ParameterizedTypeReference<LogResponse>() {
                }).getBody();
            }
        } catch (Exception e) {
            log.error("Error while fetching rig State", e);
        }
        if (data != null && !data.getData().isEmpty()) {
            return CompletableFuture.completedFuture(data.getData().get(data.getData().size() - 1).getRigState());
        } else {
            return CompletableFuture.completedFuture("");
        }
    }

    @Async
    public CompletableFuture<String> getWellMudWeight(String wellUid) {
        List<MudProperties> data = null;
        if (wellUid != null) {
            String url = consumerUri + "mudAnalysis?wellUid=" + wellUid;
            data = restTemplate.exchange(url, HttpMethod.GET, createHeaders(consumerUsername, consumerPwd), new ParameterizedTypeReference<List<MudProperties>>() {
            }).getBody();
        }
        if (data != null && !data.isEmpty()) {
            List<MudProperties.MudData> mudDataList = data.get(data.size()-1).getMudDataList();
            return CompletableFuture.completedFuture(String.valueOf(mudDataList.get(mudDataList.size()-1).getMudWeight()));
        } else {
            return CompletableFuture.completedFuture("");
        }
    }

    private HttpEntity<String> createHeaders(String username, String password) {
        HttpHeaders headers = new HttpHeaders();
        String authStr = username + ":" + password;
        String base64Creds = Base64.getEncoder().encodeToString(authStr.getBytes());
        headers.add("Authorization", "Basic " + base64Creds);
        return new HttpEntity<>("HEADERS", headers);
    }
}
