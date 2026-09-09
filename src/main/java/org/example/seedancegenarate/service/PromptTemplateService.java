package org.example.seedancegenarate.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;

/** Shared, local model guidance. Loading a template never invokes a model. */
@Service
public class PromptTemplateService {
    public record ResolvedTemplate(String guide,String resourcePath,boolean fallback) {}
    public String guide(PromptContext context) {
        return resolve(context).guide();
    }
    public ResolvedTemplate resolve(PromptContext context) {
        String model=context==null?null:context.model();
        String path=model!=null&&model.matches("[A-Za-z0-9_-]{1,128}")?"prompts/"+model+".md":null;
        String guide=path==null?null:read(path);boolean fallback=guide==null;
        if(guide==null){path="prompts/default.md";guide=read(path);}
        if(guide==null){path="builtin:default";guide="你是 AI 生成提示词专家，请把用户的粗略描述改写成一条高质量、结构清晰的提示词。";}
        if(context==null)return new ResolvedTemplate(guide,path,fallback);
        guide=guide.replace("{imageCount}",String.valueOf(context.imageCount()==null?0:context.imageCount()))
                .replace("{videoCount}",String.valueOf(context.videoCount()==null?0:context.videoCount()))
                .replace("{audioCount}",String.valueOf(context.audioCount()==null?0:context.audioCount()))
                .replace("{duration}",context.duration()==null?"":context.duration().toString())
                .replace("{ratio}",context.ratio()==null?"":context.ratio())
                .replace("{model}",model==null?"":model);
        return new ResolvedTemplate(guide,path,fallback);
    }
    private String read(String path) {
        var resource=new ClassPathResource(path);
        if(!resource.exists())return null;
        try(var input=resource.getInputStream()) {return new String(input.readAllBytes(),StandardCharsets.UTF_8).trim();}
        catch(java.io.IOException e){return null;}
    }
}
