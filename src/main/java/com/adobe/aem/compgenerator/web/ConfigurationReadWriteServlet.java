package com.adobe.aem.compgenerator.web;

import acscommons.com.google.common.primitives.Ints;
import com.adobe.aem.compgenerator.AemCompGenerator;
import com.adobe.aem.compgenerator.exceptions.GeneratorException;
import com.adobe.aem.compgenerator.javacodemodel.JavaCodeModel;
import com.adobe.aem.compgenerator.models.GenerationConfig;
import com.adobe.aem.compgenerator.models.Options;
import com.adobe.aem.compgenerator.models.ProjectSettings;
import com.adobe.aem.compgenerator.models.Property;
import com.adobe.aem.compgenerator.utils.CommonUtils;
import com.adobe.aem.compgenerator.utils.ComponentUtils;
import com.adobe.aem.compgenerator.web.model.Message;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import static com.adobe.aem.compgenerator.Constants.*;

/**
 * ConfigurationReadWriteServlet
 *
 * contains the methods to update the various
 * fields within the GenerationConfig class
 * and to generate the code on demand from the web UI
 *
 */
public class ConfigurationReadWriteServlet extends HttpServlet {
    private static final Logger LOG = LogManager.getLogger(ConfigurationReadWriteServlet.class);

    @Override
    public void init(final ServletConfig config) throws ServletException {
        super.init(config);
    }

    @Override
    /*
     * Handles retrieving the current state of the configuration json
     */
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        File configFile = new File(CONFIG_PATH);
        GenerationConfig config = CommonUtils.getComponentData(configFile);

        ObjectMapper mapper = new ObjectMapper();

        PrintWriter writer = resp.getWriter();
        writer.write(mapper.writeValueAsString(config));
        writer.close();
    }

    @Override
    /*
     * Handles resetting the config file to a default state
     */
    protected void doDelete(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        File configFile = new File(CONFIG_PATH);
        ObjectMapper mapper = new ObjectMapper();
        Message msg = new Message(false, "");
        if (configFile.exists()) {
            boolean d = configFile.delete();
            if (d) {
                // reset the config file to defaults
                String configEmptyPath = "data-config-empty.json";
                try {
                    InputStream input = AemCompGenerator.class.getResourceAsStream("/resources/" + configEmptyPath);
                    if (input == null) {
                        input = AemCompGenerator.class.getClassLoader().getResourceAsStream(configEmptyPath);
                    }
                    byte[] buffer = new byte[input.available()];
                    input.read(buffer);

                    OutputStream outStream = new FileOutputStream(CONFIG_PATH);
                    outStream.write(buffer);
                    File reConfigFile = new File(CONFIG_PATH);
                    GenerationConfig config = CommonUtils.getComponentData(reConfigFile);

                    PrintWriter writer = resp.getWriter();
                    writer.write(mapper.writeValueAsString(config));
                    writer.close();
                } catch (IOException e) {
                    LOG.error(e);
                    resp.setStatus(500);
                    throw new GeneratorException("Could not initialize data config file");
                }
            } else {
                resp.setStatus(500);
                PrintWriter writer = resp.getWriter();
                msg.setMessage("data-config.json could not be deleted.");
                writer.write(mapper.writeValueAsString(msg));
                writer.close();
            }
        } else {
            resp.setStatus(500);
            PrintWriter writer = resp.getWriter();
            msg.setMessage("data-config.json could not be deleted / reset because it was missing.");
            writer.write(mapper.writeValueAsString(msg));
            writer.close();
        }
    }

    @Override
    /*
     *  Handles generating the code from a complete GenerationConfig instance
     */
    protected void doPut(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");

        File configFile = new File(CONFIG_PATH);
        GenerationConfig config = CommonUtils.getComponentData(configFile);
        ObjectMapper mapper = new ObjectMapper();
        Message msg = new Message(false, "");

        if (!config.isValid() || !CommonUtils.isModelValid(config.getProjectSettings())) {
            resp.setStatus(500);
            PrintWriter writer = resp.getWriter();
            msg.setMessage("Validation of config file failed, required fields are missing.");
            writer.write(mapper.writeValueAsString(msg));
            writer.close();
        } else {
            String compDir = config.getProjectSettings().getAppsPath() + "/"
                    + config.getProjectSettings().getComponentPath() + "/"
                    + config.getType() + "/" + config.getName();
            config.setCompDir(compDir);

            ComponentUtils generatorUtils = new ComponentUtils(config);
            try {
                generatorUtils.buildComponent();
                //builds sling model based on config.
                if (config.getOptions() != null && config.getOptions().isHasSlingModel()) {
                    JavaCodeModel javaCodeModel = new JavaCodeModel();
                    javaCodeModel.buildSlingModel(config);
                }
            } catch (Exception e) {
                resp.setStatus(500);
                PrintWriter writer = resp.getWriter();
                msg.setMessage("Validation of config file failed, required fields are missing.");
                writer.write(mapper.writeValueAsString(msg));
                writer.close();
            }

            PrintWriter writer = resp.getWriter();
            msg.setResult(true);
            msg.setMessage("Code generated successfully!");
            writer.write(mapper.writeValueAsString(msg));
            writer.close();
        }
    }

    @Override
    protected void doPost(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        resp.setContentType("application/json; charset=UTF-8");
        resp.setCharacterEncoding("UTF-8");
        boolean updated = false;
        ObjectMapper mapper = new ObjectMapper();
        File configFile = new File(CONFIG_PATH);
        // sanity check for config file existence
        if (!configFile.exists()) {
            resp.setStatus(500);
            PrintWriter writer = resp.getWriter();
            Message msg = new Message(false, MISSING_CONFIG_MSG);
            writer.write(mapper.writeValueAsString(msg));
            writer.close();
            return;
        }
        GenerationConfig config = CommonUtils.getComponentData(configFile);
        ProjectSettings projectSettings = config.getProjectSettings();
        Options options = config.getOptions();
        if (req.getContentLength() <= 0) {
            resp.setStatus(500);
        } else {
            // get config params as json from request body
            String body = req.getReader().lines().collect(Collectors.joining(System.lineSeparator()));
            JsonNode reConfig = mapper.readTree(body);

            if (reConfig.has("codeOwner")) {
                updated = true;
                String codeOwner = reConfig.get("codeOwner").textValue();
                projectSettings.setCodeOwner(codeOwner);
            }
            if (reConfig.has("bundlePath")) {
                updated = true;
                String val = reConfig.get("bundlePath").textValue();
                projectSettings.setBundlePath(val);
            }
            if (reConfig.has("testPath")) {
                updated = true;
                String val = reConfig.get("testPath").textValue();
                projectSettings.setTestPath(val);
            }
            if (reConfig.has("appsPath")) {
                updated = true;
                String val = reConfig.get("appsPath").textValue();
                projectSettings.setAppsPath(val);
            }
            if (reConfig.has("componentPath")) {
                updated = true;
                String val = reConfig.get("componentPath").textValue();
                projectSettings.setComponentPath(val);
            }
            if (reConfig.has("modelInterfacePackage")) {
                updated = true;
                String val = reConfig.get("modelInterfacePackage").textValue();
                projectSettings.setModelInterfacePackage(val);
            }
            if (reConfig.has("modelImplPackage")) {
                updated = true;
                String val = reConfig.get("modelImplPackage").textValue();
                projectSettings.setModelImplPackage(val);
            }
            if (reConfig.has("copyrightYear")) {
                updated = true;
                String val = reConfig.get("copyrightYear").textValue();
                projectSettings.setYear(val);
            }
            if (reConfig.has("componentTitle")) {
                updated = true;
                String val = reConfig.get("componentTitle").textValue();
                config.setTitle(val);
            }
            if (reConfig.has("componentNodeName")) {
                updated = true;
                String val = reConfig.get("componentNodeName").textValue();
                config.setName(val);
            }
            if (reConfig.has("componentGroup")) {
                updated = true;
                String val = reConfig.get("componentGroup").textValue();
                config.setGroup(val);
            }
            if (reConfig.has("componentType")) {
                updated = true;
                String val = reConfig.get("componentType").textValue();
                config.setType(val);
            }
            if (reConfig.has("modelAdapters")) {
                updated = true;
                JsonNode val = reConfig.get("modelAdapters");
                ArrayList<String> adapters = new ArrayList<>();
                if (val.isArray()) {
                    for (JsonNode jsonNode : val) {
                        String adaptable = jsonNode.get("value").asText();
                        adapters.add(adaptable);
                    }
                }
                options.setModelAdaptables(adapters.toArray(new String[adapters.size()]));
            }
            if (reConfig.has("js")) {
                updated = true;
                boolean val = reConfig.get("js").booleanValue();
                options.setHasJs(val);
            }
            if (reConfig.has("jsTxt")) {
                updated = true;
                boolean val = reConfig.get("jsTxt").booleanValue();
                options.setHasJsTxt(val);
            }
            if (reConfig.has("css")) {
                updated = true;
                boolean val = reConfig.get("css").booleanValue();
                options.setHasCss(val);
            }
            if (reConfig.has("cssTxt")) {
                updated = true;
                boolean val = reConfig.get("cssTxt").booleanValue();
                options.setHasCssTxt(val);
            }
            if (reConfig.has("html")) {
                updated = true;
                boolean val = reConfig.get("html").booleanValue();
                options.setHasHtml(val);
            }
            if (reConfig.has("htmlContent")) {
                updated = true;
                boolean val = reConfig.get("htmlContent").booleanValue();
                options.setHtmlContent(val);
            }
            if (reConfig.has("slingModel")) {
                updated = true;
                boolean val = reConfig.get("slingModel").booleanValue();
                options.setHasSlingModel(val);
            }
            if (reConfig.has("testClass")) {
                updated = true;
                boolean val = reConfig.get("testClass").booleanValue();
                options.setHasTestClass(val);
            }
            if (reConfig.has("contentExporter")) {
                updated = true;
                boolean val = reConfig.get("contentExporter").booleanValue();
                options.setAllowExporting(val);
            }
            if (reConfig.has("genericJavadoc")) {
                updated = true;
                boolean val = reConfig.get("genericJavadoc").booleanValue();
                options.setHasGenericJavadoc(val);
            }
            if (reConfig.has("junitMajorVersion")) {
                updated = true;
                JsonNode jsonNode = reConfig.get("junitMajorVersion");
                if (jsonNode.isInt()) {
                    options.setJunitVersion(jsonNode.asInt());
                } else {
                    String val = reConfig.get("junitMajorVersion").textValue();
                    if (Objects.nonNull(val)) {
                        Integer valInt = Ints.tryParse(val);
                        options.setJunitVersion(Objects.nonNull(valInt) ? valInt : 5);
                    }
                }
            }
            config.setProjectSettings(projectSettings);
            config.setOptions(options);

            String compDir = projectSettings.getAppsPath() + "/"
                    + projectSettings.getComponentPath() + "/"
                    + config.getType() + "/" + config.getName();
            config.setCompDir(compDir);

            if (updated) {
                DefaultPrettyPrinter pp = new DefaultPrettyPrinter();
                pp.indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE);
                mapper.writer(pp).writeValue(configFile, config);
            }

        }

        PrintWriter writer = resp.getWriter();
        Message msg = new Message(true, updated ? UPDATED_MSG : NO_UPDATE_MSG);
        writer.write(mapper.writeValueAsString(msg));
        writer.close();
    }
}
