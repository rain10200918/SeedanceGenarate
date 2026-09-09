package org.example.seedancegenarate.agent.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.example.seedancegenarate.agent.skill.SkillDescriptor;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Wire-only schema. Business decisions and Skill validation remain the execution authority. */
final class AgentDecisionSchema {
    private final ObjectMapper json;
    private final Map<String, ObjectNode> branches = new LinkedHashMap<>();

    AgentDecisionSchema(ObjectMapper json, List<SkillDescriptor> skills) {
        this.json=json;
        for(String type:List.of("RESPOND","COMPLETE","ASK_USER")) {
            var branch=base(type,true);
            if(type.equals("ASK_USER")) {
                var option=object(); var p=option.withObject("properties");
                p.set("id",text(1,32).put("pattern","^[A-Za-z0-9_-]+$"));p.set("label",text(1,120));
                option.putArray("required").add("id").add("label");
                var options=json.createObjectNode().put("type","array").put("minItems",2).put("maxItems",5);
                options.set("items",option);branch.withObject("properties").set("options",options);
            }
            branches.put(type,branch);
        }
        for(var skill:skills) {
            if(skill.inputSchema()==null || !skill.inputSchema().isObject())
                throw new IllegalStateException("Planner Skill schema missing");
            var branch=base("CALL_SKILL",false);var p=branch.withObject("properties");
            p.set("skillId",literal(skill.id()));p.set("input",skill.inputSchema().deepCopy());
            branch.withArray("required").add("skillId").add("input");
            branches.put("CALL_SKILL:"+skill.id(),branch);
        }
    }

    JsonNode wire() {
        var root=object();root.putArray("required").add("decision");
        var decision=root.withObject("properties").putObject("decision").putArray("anyOf");
        branches.values().forEach(b->decision.add(strict(b)));
        return root;
    }

    JsonNode decision(JsonNode envelope) {
        if(envelope==null || !envelope.isObject() || envelope.size()!=1 || !envelope.has("decision"))
            throw invalid("$", "WIRE_ENVELOPE", "必须仅包含decision对象，不接受裸Decision或额外字段");
        JsonNode decision=envelope.get("decision");
        if(!decision.isObject()) throw invalid("$.decision","OBJECT_REQUIRED","decision必须为对象");
        String type=decision.path("type").asText();
        String key=type.equals("CALL_SKILL")?type+":"+decision.path("skillId").asText():type;
        var branch=branches.get(key);
        if(branch==null) throw invalid("$.type","ACTION_NOT_AVAILABLE","动作与技能必须属于本轮Schema");
        var result=decision.deepCopy();normalize(result,branch,"$.decision");return result;
    }

    private ObjectNode base(String type,boolean textRequired) {
        var b=object();var p=b.withObject("properties");
        p.set("type",literal(type));p.set("text",text(textRequired?1:0,4000));p.set("summary",text(0,3000));
        var required=b.putArray("required").add("type");if(textRequired)required.add("text");return b;
    }
    private ObjectNode object() {var n=json.createObjectNode().put("type","object").put("additionalProperties",false);n.putObject("properties");return n;}
    private ObjectNode text(int min,int max) {return json.createObjectNode().put("type","string").put("minLength",min).put("maxLength",max);}
    private ObjectNode literal(String value) {var n=json.createObjectNode().put("type","string");n.putArray("enum").add(value);return n;}
    private boolean required(JsonNode schema,String field) {for(var n:schema.path("required"))if(n.asText().equals(field))return true;return false;}

    private JsonNode strict(JsonNode schema) {
        ObjectNode copy=schema.deepCopy();
        var names=new java.util.ArrayList<String>();copy.fieldNames().forEachRemaining(names::add);
        names.stream().filter(n->n.startsWith("x-")).forEach(copy::remove);
        if("object".equals(copy.path("type").asText())) {
            copy.put("additionalProperties",false);var p=copy.withObject("properties");var all=json.createArrayNode();
            var fields=new java.util.ArrayList<String>();p.fieldNames().forEachRemaining(fields::add);
            for(String field:fields) {
                JsonNode value=strict(p.get(field));
                if(!required(schema,field)) {
                    var nullable=json.createObjectNode();nullable.putArray("anyOf").add(value).add(json.createObjectNode().put("type","null"));value=nullable;
                }
                p.set(field,value);all.add(field);
            }
            copy.set("required",all);
        } else if(copy.has("items")) copy.set("items",strict(copy.get("items")));
        return copy;
    }

    private void normalize(JsonNode value,JsonNode schema,String path) {
        if("object".equals(schema.path("type").asText())) {
            if(!value.isObject()) throw invalid(path,"OBJECT_REQUIRED","必须为对象");
            var properties=schema.path("properties");var fields=new java.util.ArrayList<String>();value.fieldNames().forEachRemaining(fields::add);
            for(String f:fields) if(!properties.has(f)) throw invalid(path,"UNKNOWN_FIELD","存在本轮Schema未定义的字段");
            properties.fieldNames().forEachRemaining(f->{
                if(!value.has(f)) {
                    if(required(schema,f)) throw invalid(path+"."+f,"REQUIRED","缺少必填字段");
                    return;
                }
                JsonNode child=value.get(f);
                if(child.isNull()) {
                    if(required(schema,f)) throw invalid(path+"."+f,"REQUIRED_NULL","必填字段不得为null");
                    ((ObjectNode)value).remove(f);
                } else normalize(child,properties.get(f),path+"."+f);
            });
        } else if("array".equals(schema.path("type").asText()) && value.isArray()) {
            for(int i=0;i<value.size();i++)normalize(value.get(i),schema.path("items"),path+"["+i+"]");
        }
    }
    private static InvalidAgentDecisionException invalid(String path,String code,String reason) {
        return new InvalidAgentDecisionException(code+" "+path+": "+reason);
    }
}
