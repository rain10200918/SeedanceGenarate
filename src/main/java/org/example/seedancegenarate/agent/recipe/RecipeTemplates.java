package org.example.seedancegenarate.agent.recipe;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Bundled content only: never loads user-supplied paths, code, or remote definitions. */
public final class RecipeTemplates {
    public record Source(String name,String url,String license) {}
    public record Definition(String id,int version,String name,String description,String instruction,
            RecipeDefinition definition,List<Source> sources,List<String> limitations) {}
    public record Template(String id,int version,String name,String description,String instruction,
            RecipeDefinition definition,List<Source> sources,List<String> limitations,
            String templateHash,List<String> missingCapabilities) {}
    private final ObjectMapper json;
    public RecipeTemplates(ObjectMapper json) {this.json=json;}

    public List<Template> list(RecipeCompiler compiler) {
        try(var stream=RecipeTemplates.class.getResourceAsStream("/agent/recipe-templates.json")) {
            if(stream==null)throw new IllegalStateException("Missing bundled recipe templates");
            List<Definition> definitions=json.readValue(stream,new TypeReference<>() {});
            Set<String> ids=new HashSet<>();List<Template> result=new ArrayList<>();
            for(var entry:definitions) {
                validate(entry);
                if(!ids.add(entry.id()))throw new IllegalStateException("Duplicate template id");
                String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(json.writeValueAsString(entry).getBytes(StandardCharsets.UTF_8)));
                result.add(new Template(entry.id(),entry.version(),entry.name(),entry.description(),entry.instruction(),
                        entry.definition(),entry.sources(),entry.limitations(),hash,compiler.missing(entry.definition())));
            }
            return List.copyOf(result);
        } catch(Exception e) {throw new BusinessException(503,"技能模板暂不可用，请稍后重试");}
    }
    public Template get(String id,RecipeCompiler compiler) {
        return list(compiler).stream().filter(t->t.id().equals(id)).findFirst()
                .orElseThrow(()->BusinessException.notFound("技能模板不存在"));
    }
    private static void validate(Definition entry) {
        if(entry==null||entry.id()==null||!entry.id().matches("[a-z0-9-]{1,64}")||entry.version()<1)
            throw new IllegalStateException("Invalid template identity");
        text(entry.name(),30);text(entry.description(),500);text(entry.instruction(),24000);
        entry.definition().validate();
        if(entry.sources()==null||entry.limitations()==null)throw new IllegalStateException("Missing template provenance");
        for(var source:entry.sources()) {
            text(source.name(),200);text(source.url(),1000);text(source.license(),200);
            var uri=java.net.URI.create(source.url());
            if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getUserInfo()!=null)
                throw new IllegalStateException("Invalid source URL");
        }
        for(String limitation:entry.limitations())text(limitation,1000);
    }
    private static void text(String value,int max) {
        if(value==null||value.isBlank()||value.length()>max)throw new IllegalStateException("Invalid template text");
    }
}
