package org.example.seedancegenarate.agent.recipe;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Supplier;

@Service
public class RecipeCatalog {
    private final JdbcTemplate db; private final TransactionTemplate tx;
    private final ObjectMapper json; private final RecipeCompiler compiler;
    public RecipeCatalog(JdbcTemplate db,TransactionTemplate tx,ObjectMapper json,RecipeCompiler compiler) {
        this.db=db;this.tx=tx;this.json=json;this.compiler=compiler;
    }
    public record Save(String clientActionId,String recipeId,Long expectedRevision,String name,String description,String instruction) {}
    public record Compile(String clientActionId,Long expectedRevision) {}
    public record Publish(String clientActionId,Long expectedRevision,String compilationId,String definitionHash,Boolean confirmed) {}
    public record Enable(String clientActionId,Long expectedRevision,Boolean enabled) {}
    public record ImportTemplate(String clientActionId,Integer templateVersion,String templateHash) {}
    public record Compilation(String id,long draftRevision,RecipeDefinition definition,String definitionHash,List<String> warnings,List<String> missingCapabilities) {}
    public record RecipeView(String id,String name,String description,String instruction,boolean enabled,long revision,
            int latestVersion,String latestVersionId,Compilation compilation,List<String> missingCapabilities) {}
    public record Published(String id,String recipeId,int version,String name,String description,String instruction,
            RecipeDefinition definition,String contentHash,List<String> missingCapabilities) {}

    public List<RecipeView> list(long user) {
        owner(user);return db.query("SELECT * FROM creative_recipe WHERE owner_id=? ORDER BY updated_at DESC,id DESC LIMIT 100",(r,n)->view(r),user);
    }
    public RecipeView get(long user,String id) {return owned(user,id,false);}
    public List<RecipeTemplates.Template> templates(long user) {owner(user);return new RecipeTemplates(json).list(compiler);}
    public RecipeView importTemplate(long user,String templateId,ImportTemplate request) {
        if(request==null||request.templateVersion()==null||request.templateVersion()<1)bad();
        key(request.clientActionId());text(templateId,64);
        if(request.templateHash()==null||!request.templateHash().matches("[a-f0-9]{64}"))bad();
        String id=UUID.randomUUID().toString();
        // The stable request includes the template id, never the newly allocated private draft id.
        return write(user,request.clientActionId(),id,"IMPORT_TEMPLATE",List.of(templateId,request),()->{
            var template=new RecipeTemplates(json).get(templateId,compiler);
            if(template.version()!=request.templateVersion()||!template.templateHash().equals(request.templateHash()))
                throw BusinessException.conflict("技能模板已更新，请重新查看后添加");
            template.definition().validate();
            List<String> warnings=new ArrayList<>(template.limitations());
            for(var source:template.sources())warnings.add("来源："+source.name()+" · "+source.license()+" · "+source.url());
            warnings.add("这是平台适配的确定性规则预览；请确认后发布。编辑内容后需重新解析，生成费用仍须平台审批。");
            var preview=new Compilation(UUID.randomUUID().toString(),1,template.definition(),hash(encode(template.definition())),
                    List.copyOf(warnings),compiler.missing(template.definition()));
            db.update("INSERT INTO creative_recipe(id,owner_id,name,description,instruction,compilation_json) VALUES(?,?,?,?,?,?)",
                    id,user,template.name(),template.description(),template.instruction(),encode(preview));
            return owned(user,id,false);
        });
    }
    public RecipeView save(long user,Save request) {
        if(request==null) bad(); key(request.clientActionId()); text(request.name(),30);text(request.description(),500);text(request.instruction(),24000);
        boolean create=request.recipeId()==null;
        if(create&&request.expectedRevision()!=null||!create&&request.expectedRevision()==null) bad();
        String id=create?UUID.randomUUID().toString():request.recipeId();
        return write(user,request.clientActionId(),id,"SAVE",request,()->{
            if(create) {
                db.update("INSERT INTO creative_recipe(id,owner_id,name,description,instruction) VALUES(?,?,?,?,?)",id,user,request.name(),request.description(),request.instruction());
            } else {
                var current=owned(user,id,true); revision(current,request.expectedRevision());
                db.update("UPDATE creative_recipe SET name=?,description=?,instruction=?,compilation_json=NULL,revision=revision+1,updated_at=CURRENT_TIMESTAMP WHERE id=?",request.name(),request.description(),request.instruction(),id);
            }
            return owned(user,id,false);
        });
    }
    public RecipeView enable(long user,String id,Enable request) {
        if(request==null||request.enabled()==null) bad(); key(request.clientActionId());
        return write(user,request.clientActionId(),id,"ENABLE",request,()->{
            var current=owned(user,id,true); revision(current,request.expectedRevision());
            db.update("UPDATE creative_recipe SET enabled=?,revision=revision+1,compilation_json=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",request.enabled(),id);
            return owned(user,id,false);
        });
    }
    public RecipeView publish(long user,String id,Publish request) {
        if(request==null||!Boolean.TRUE.equals(request.confirmed())) throw BusinessException.badRequest("请先查看并确认解析规则");
        key(request.clientActionId()); text(request.compilationId(),64);text(request.definitionHash(),64);
        return write(user,request.clientActionId(),id,"PUBLISH",request,()->{
            var current=owned(user,id,true);revision(current,request.expectedRevision());var preview=current.compilation();
            if(preview==null||preview.draftRevision()!=current.revision()||!preview.id().equals(request.compilationId())||!preview.definitionHash().equals(request.definitionHash()))
                throw BusinessException.conflict("解析预览已变化，请重新查看并确认");
            preview.definition().validate();
            String versionId=UUID.randomUUID().toString();int version=current.latestVersion()+1;
            String contentHash=hash(encode(List.of(current.name(),current.description(),current.instruction(),preview.definition())));
            db.update("INSERT INTO creative_recipe_version(id,recipe_id,version,name,description,instruction,definition_json,content_hash,compiler_version) VALUES(?,?,?,?,?,?,?,?,?)",
                    versionId,id,version,current.name(),current.description(),current.instruction(),encode(preview.definition()),contentHash,RecipeCompiler.VERSION);
            db.update("UPDATE creative_recipe SET latest_version=?,latest_version_id=?,revision=revision+1,compilation_json=NULL,updated_at=CURRENT_TIMESTAMP WHERE id=?",version,versionId,id);
            return owned(user,id,false);
        });
    }
    public RecipeView compile(long user,String id,Compile request) {
        if(request==null) bad();key(request.clientActionId());owner(user);text(id,64);
        String requestHash=hash(encode(List.of("COMPILE",id,request)));
        RecipeView replay=replay(user,request.clientActionId(),requestHash);if(replay!=null)return replay;
        RecipeView source;
        try {
            source=tx.execute(t->{
                var current=owned(user,id,true);revision(current,request.expectedRevision());
                db.update("INSERT INTO creative_recipe_command(owner_id,request_id,recipe_id,request_hash,status) VALUES(?,?,?,?, 'RUNNING')",user,request.clientActionId(),id,requestHash);
                return current;
            });
        } catch(DuplicateKeyException e) {return replay(user,request.clientActionId(),requestHash);}
        try {
            String compilationId=UUID.randomUUID().toString();
            var definition=compiler.compile(user,compilationId,source.name(),source.instruction());
            var preview=new Compilation(compilationId,source.revision(),definition,hash(encode(definition)),
                    List.of("解析可能遗漏或理解错误，请对照原文确认；未支持的要求只作为创作指导，不能绕过平台权限与费用审批。"),compiler.missing(definition));
            return tx.execute(t->{
                var current=owned(user,id,true);revision(current,source.revision());
                db.update("UPDATE creative_recipe SET compilation_json=?,updated_at=CURRENT_TIMESTAMP WHERE id=?",encode(preview),id);
                var result=owned(user,id,false); complete(user,request.clientActionId(),result); return result;
            });
        } catch(org.springframework.dao.DataAccessException e) {throw e;
        } catch(Exception e) {
            int code=e instanceof BusinessException b&&b.getCode()==409?409:422;
            db.update("UPDATE creative_recipe_command SET status='FAILED',error_code=? WHERE owner_id=? AND request_id=? AND status='RUNNING'",code,user,request.clientActionId());
            if(e instanceof BusinessException b&&b.getCode()==409)throw b;
            throw new BusinessException(422,"技能解析未成功，草稿已保留，请明确重新解析");
        }
    }
    public Published requireRunnable(long user,String versionId) {return published(user,versionId,true);}
    public Published published(long user,String versionId,boolean requireEnabled) {
        owner(user);text(versionId,64);
        var versions=db.query("SELECT * FROM creative_recipe_version WHERE id=?",(r,n)->new Published(r.getString("id"),r.getString("recipe_id"),r.getInt("version"),r.getString("name"),r.getString("description"),r.getString("instruction"),decode(r.getString("definition_json"),RecipeDefinition.class),r.getString("content_hash"),List.of()),versionId);
        if(versions.isEmpty())throw BusinessException.notFound("技能版本不存在");
        var version=versions.get(0);var recipe=owned(user,version.recipeId(),false);
        var missing=compiler.missing(version.definition());
        if(requireEnabled&&!recipe.enabled())throw BusinessException.conflict("此技能已停用");
        if(requireEnabled&&!missing.isEmpty())throw BusinessException.conflict("技能缺少可执行能力："+String.join("、",missing));
        return new Published(version.id(),version.recipeId(),version.version(),version.name(),version.description(),version.instruction(),version.definition(),version.contentHash(),missing);
    }
    private RecipeView write(long user,String key,String id,String kind,Object request,Supplier<RecipeView> mutation) {
        owner(user);text(id,64);String requestHash=hash(encode(Arrays.asList(kind,("SAVE".equals(kind)||"IMPORT_TEMPLATE".equals(kind))?null:id,request)));
        RecipeView old=replay(user,key,requestHash);if(old!=null)return old;
        try {
            return tx.execute(t->{
                db.update("INSERT INTO creative_recipe_command(owner_id,request_id,recipe_id,request_hash,status) VALUES(?,?,?,?, 'RUNNING')",user,key,id,requestHash);
                var result=mutation.get();complete(user,key,result);return result;
            });
        } catch(DuplicateKeyException e) {return replay(user,key,requestHash);}
    }
    private RecipeView replay(long user,String key,String hash) {
        var rows=db.queryForList("SELECT request_hash,status,response_json,error_code,created_at FROM creative_recipe_command WHERE owner_id=? AND request_id=?",user,key);
        if(rows.isEmpty())return null;var row=rows.get(0);
        if(!hash.equals(row.get("request_hash")))throw BusinessException.conflict("请求编号已用于其他内容，请刷新后重试");
        if("SUCCEEDED".equals(row.get("status")))return decode((String)row.get("response_json"),RecipeView.class);
        if("FAILED".equals(row.get("status")))throw new BusinessException(((Number)row.get("error_code")).intValue(),"上次解析未成功，请使用重新解析发起新请求");
        // Never reissue a potentially billed model request after a crash or timeout.
        var created=(java.sql.Timestamp)row.get("created_at");
        if(created.toInstant().plusSeconds(180).isBefore(java.time.Instant.now()))
            throw new BusinessException(410,"上次解析未完成或已中断，草稿已保留；请显式重新解析");
        throw new BusinessException(425,"技能正在解析，请稍后使用同一请求重试");
    }
    private void complete(long user,String key,RecipeView result) {
        if(db.update("UPDATE creative_recipe_command SET status='SUCCEEDED',response_json=? WHERE owner_id=? AND request_id=? AND status='RUNNING'",encode(result),user,key)!=1)
            throw BusinessException.conflict("技能操作状态已变化");
    }
    private RecipeView owned(long user,String id,boolean lock) {
        owner(user);text(id,64);
        var rows=db.query("SELECT * FROM creative_recipe WHERE id=? AND owner_id=?"+(lock?" FOR UPDATE":""),(r,n)->view(r),id,user);
        if(rows.isEmpty())throw BusinessException.notFound("技能不存在");return rows.get(0);
    }
    private RecipeView view(java.sql.ResultSet r) throws java.sql.SQLException {
        String preview=r.getString("compilation_json");
        String versionId=r.getString("latest_version_id");List<String> missing=List.of();
        if(versionId!=null) {
            var definitions=db.query("SELECT definition_json FROM creative_recipe_version WHERE id=?",(v,n)->decode(v.getString(1),RecipeDefinition.class),versionId);
            if(!definitions.isEmpty())missing=compiler.missing(definitions.get(0));
        }
        return new RecipeView(r.getString("id"),r.getString("name"),r.getString("description"),r.getString("instruction"),r.getBoolean("enabled"),r.getLong("revision"),r.getInt("latest_version"),versionId,preview==null?null:decode(preview,Compilation.class),missing);
    }
    private static void revision(RecipeView current,Long expected) {
        if(expected==null||expected<1)bad();if(current.revision()!=expected)throw BusinessException.conflict("技能已在其他页面更新，请刷新后重试");
    }
    private String encode(Object value) {try{return json.writeValueAsString(value);}catch(Exception e){throw BusinessException.badRequest("技能数据无法序列化");}}
    private <T>T decode(String value,Class<T> type) {try{return json.readValue(value,type);}catch(Exception e){throw new BusinessException(503,"技能记录暂不可读，请联系管理员");}}
    private static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    private static void key(String key) {if(key==null||!key.matches("[A-Za-z0-9_-]{1,64}"))bad();}
    private static void text(String value,int max) {if(value==null||value.isBlank()||value.length()>max)bad();}
    private static void owner(long user) {if(user<1)throw BusinessException.unauthorized("请先登录");}
    private static void bad() {throw BusinessException.badRequest("技能请求字段为空或超出长度限制");}
}
