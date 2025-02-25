package com.redhat.vertx.pipeline.templates;

import java.util.Map;
import java.util.ServiceLoader;
import java.util.logging.Logger;

import com.hubspot.jinjava.Jinjava;
import com.hubspot.jinjava.JinjavaConfig;
import com.hubspot.jinjava.interpret.Context;
import com.hubspot.jinjava.interpret.RenderResult;
import com.hubspot.jinjava.interpret.TemplateError;
import com.hubspot.jinjava.lib.filter.Filter;

public class JinjaTemplateProcessor implements TemplateProcessor {
    private Logger logger = Logger.getLogger(this.getClass().getName());
    private final Jinjava jinjava;

    public JinjaTemplateProcessor() {
        JinjavaConfig.Builder builder = JinjavaConfig.newBuilder();
        builder.withFailOnUnknownTokens(true);
        JinjavaConfig config = builder.build();
        jinjava = new Jinjava(config);
        Context ctx = jinjava.getGlobalContext();
        ServiceLoader.load(Filter.class).forEach(ctx::registerFilter);
        ServiceLoader.load(JinjaFunctionDefinition.class)
                .forEach(fds -> fds.getFunctionDefinitions().forEach(ctx::registerFunction));
    }

    @Override
    public String applyTemplate(Map<String,Object> env, String str) {
        RenderResult rr = jinjava.renderForResult(str, env);
        if (rr.hasErrors()) {
            rr.getErrors().stream().filter(te -> te.getSeverity() == TemplateError.ErrorType.FATAL)
                    .iterator().forEachRemaining(te ->logger.severe(te.toString()));
            rr.getErrors().stream().filter(te -> te.getSeverity() == TemplateError.ErrorType.WARNING)
                    .iterator().forEachRemaining(te ->logger.warning(te.toString()));
            return null;
        }
        return rr.getOutput();
    }
}
