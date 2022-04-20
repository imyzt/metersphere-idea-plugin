package org.metersphere.exporter;

import com.alibaba.fastjson.JSONObject;
import com.google.common.base.Strings;
import com.google.gson.Gson;
import com.intellij.notification.Notification;
import com.intellij.notification.NotificationDisplayType;
import com.intellij.notification.NotificationGroup;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiJavaFile;
import com.intellij.psi.impl.source.PsiClassImpl;
import com.intellij.psi.javadoc.PsiDocTag;
import org.apache.http.HttpEntity;
import org.apache.http.HttpStatus;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.ContentType;
import org.apache.http.entity.mime.MultipartEntityBuilder;
import org.apache.http.impl.client.CloseableHttpClient;
import org.jetbrains.annotations.NotNull;
import org.metersphere.AppSettingService;
import org.metersphere.constants.MSApiConstants;
import org.metersphere.constants.PluginConstants;
import org.metersphere.model.PostmanModel;
import org.metersphere.state.AppSettingState;
import org.metersphere.utils.HttpFutureUtils;
import org.metersphere.utils.MSApiUtil;
import org.metersphere.utils.ProgressUtil;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class MeterSphereExporter implements IExporter {
    private Logger logger = Logger.getInstance(MeterSphereExporter.class);
    private final PostmanExporter postmanExporter = new PostmanExporter();
    private final AppSettingService appSettingService = AppSettingService.getInstance();

    private static NotificationGroup notificationGroup;

    static {
        notificationGroup = new NotificationGroup("MsExport.NotificationGroup", NotificationDisplayType.BALLOON, true);
    }

    @Override
    public boolean export(PsiElement psiElement) throws IOException {
        if (!MSApiUtil.test(appSettingService.getState())) {
            throw new RuntimeException(PluginConstants.EXCEPTIONCODEMAP.get(1));
        }
        List<PsiJavaFile> files = new LinkedList<>();
        postmanExporter.getFile(psiElement, files);
        files = files.stream().filter(f ->
                f instanceof PsiJavaFile
        ).collect(Collectors.toList());
        if (files.size() == 0) {
            throw new RuntimeException(PluginConstants.EXCEPTIONCODEMAP.get(2));
        }

        AppSettingState state = appSettingService.getState();
        JSONObject param = buildParam(state, psiElement);
        String msContextPath = param.getString("msContextPath");
        List<PostmanModel> postmanModels = postmanExporter.transform(files, false, appSettingService.getState(), msContextPath);
        if (postmanModels.size() == 0) {
            throw new RuntimeException(PluginConstants.EXCEPTIONCODEMAP.get(3));
        }
        File temp = File.createTempFile(UUID.randomUUID().toString(), null);
        BufferedWriter bufferedWriter = new BufferedWriter(new FileWriter(temp));
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("item", postmanModels);
        JSONObject info = new JSONObject();
        info.put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json");
        String dateTime = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        // 此处首先选择@menu, 然后取配置名称
        String exportName = Arrays.stream(((PsiClassImpl) psiElement.getChildren()[4]).getDocComment().getTags())
                .filter(d -> d.getName().contains("menu")).map(PsiDocTag::getValueElement).map(PsiElement::getText)
                .findFirst().orElse(appSettingService.getState().getExportModuleName());

        info.put("name", exportName);
        info.put("description", "exported at " + dateTime);
        jsonObject.put("info", info);
        bufferedWriter.write(new Gson().toJson(jsonObject));
        bufferedWriter.flush();
        bufferedWriter.close();

        boolean r = uploadToServer(temp, param);
        if (r) {
            ProgressUtil.show(("Export to MeterSphere success!"));
        } else {
            ProgressUtil.show(("Export to MeterSphere fail!"));
        }
        if (temp.exists()) {
            temp.delete();
        }
        return r;
    }

    private boolean uploadToServer(File file, JSONObject param) {
        ProgressUtil.show((String.format("Start to sync to MeterSphere Server")));
        CloseableHttpClient httpclient = HttpFutureUtils.getOneHttpClient();
        AppSettingState state = appSettingService.getState();
        String url = state.getMeterSphereAddress() + "/api/definition/import";
        HttpPost httpPost = new HttpPost(url);// 创建httpPost
        httpPost.setHeader("Accept", "application/json, text/plain, */*");
        httpPost.setHeader("accesskey", appSettingService.getState().getAccesskey());
        httpPost.setHeader("signature", MSApiUtil.getSinature(appSettingService.getState()));
        CloseableHttpResponse response = null;


        HttpEntity formEntity = MultipartEntityBuilder.create().addBinaryBody("file", file, ContentType.APPLICATION_JSON, null)
                .addBinaryBody("request", param.toJSONString().getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON, null).build();

        httpPost.setEntity(formEntity);
        try {
            response = httpclient.execute(httpPost);
            StatusLine status = response.getStatusLine();
            int statusCode = status.getStatusCode();
            if (statusCode == HttpStatus.SC_OK || statusCode == HttpStatus.SC_CREATED) {
                return true;
            } else {
                return false;
            }
        } catch (Exception e) {
            logger.error("上传至 MS 失败！", e);
        } finally {
            if (response != null) {
                try {
                    response.close();
                } catch (IOException e) {
                    logger.error("关闭 response 失败！", e);
                }
            }
            try {
                httpclient.close();
            } catch (IOException e) {
                logger.error("关闭 httpclient 失败！", e);
            }
        }
        return false;
    }

    @NotNull
    private JSONObject buildParam(AppSettingState state, PsiElement psiElement) {
        JSONObject param = new JSONObject();
        param.put("modeId", MSApiUtil.getModeId(state.getModeId()));
        if (state.getModule() == null) {
            throw new RuntimeException("no module selected ! please check your rights");
        }

        Project project = psiElement.getProject();
        PsiFile psiFile = psiElement.getContainingFile();

        String msProjectId = null;
        String msModuleId = null;
        String msContextPath = null;
        // 获取配置
        try {
            String projectConfig = new String(project.getProjectFile().contentsToByteArray(), "utf-8");
            String[] modules = projectConfig.split("moduleList\">");
            if (modules.length > 1) {
                String[] moduleList = modules[1].split("</")[0].split(",");
                String virtualFile = psiFile.getVirtualFile().getPath();
                for (int i = 0; i < moduleList.length; i++) {
                    if (virtualFile.contains(moduleList[i])) {
                        msProjectId = projectConfig.split(moduleList[i] + "\\.msProjectId\">")[1].split("</")[0];
                        msModuleId = projectConfig.split(moduleList[i] + "\\.msModuleId\">")[1].split("</")[0];
                        msContextPath = projectConfig.split(moduleList[i] + "\\.msContextPath\">")[1].split("</")[0];
                        break;
                    }
                }
            } else {
                msProjectId = projectConfig.split("msProjectId\">")[1].split("</")[0];
                msModuleId = projectConfig.split("msModuleId\">")[1].split("</")[0];
                msContextPath = projectConfig.split("msContextPath\">")[1].split("</")[0];
            }
        } catch (Exception e2) {
            Notification error = notificationGroup.createNotification("get config error:" + e2.getMessage(), NotificationType.ERROR);
            Notifications.Bus.notify(error, project);
            throw new RuntimeException("get config error:" + e2.getMessage());
        }
        // 配置校验
        if (Strings.isNullOrEmpty(msProjectId) || Strings.isNullOrEmpty(msModuleId) || Strings.isNullOrEmpty(msContextPath)) {
            Notification error = notificationGroup.createNotification("please check config,[projectToken,projectId,yapiUrl,projectType]", NotificationType.ERROR);
            Notifications.Bus.notify(error, project);
            throw new RuntimeException("please check config,[projectToken,projectId,yapiUrl,projectType]");
        }

        param.put("moduleId", msModuleId);
        param.put("platform", "Postman");
        param.put("model", "definition");
        param.put("projectId", msProjectId);
        param.put("msContextPath", msContextPath);
        if (state.getProjectVersion() != null && state.isSupportVersion()) {
            param.put("versionId", state.getProjectVersion().getId());
        }
        if (MSApiUtil.getModeId(state.getModeId()).equalsIgnoreCase(MSApiConstants.MODE_FULLCOVERAGE)) {
            if (state.getUpdateVersion() != null && state.isSupportVersion()) {
                param.put("updateVersionId", state.getUpdateVersion().getId());
            }
        }
        return param;
    }

}
