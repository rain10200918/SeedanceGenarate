package org.example.seedancegenarate.agent.recipe;

import java.util.List;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import org.example.seedancegenarate.exception.BusinessException;

/** Published guidance plus a finite, non-executable policy vocabulary. */
public record RecipeDefinition(String instruction, List<RequiredInput> requiredInputs,
        List<Variable> variables, List<Stage> stages, List<String> requiredCapabilities,
        List<AcceptanceRule> acceptanceRules) {
    public RecipeDefinition {
        requiredInputs=copy(requiredInputs);variables=copy(variables);stages=copy(stages);
        requiredCapabilities=copy(requiredCapabilities);acceptanceRules=copy(acceptanceRules);
    }
    public record RequiredInput(String type, int minCount) {}
    public record Variable(String id, String label, boolean required, List<String> options) {
        public Variable {options=copy(options);}
    }
    public record Stage(String id, String title, String instruction, List<String> allowedSkills,
            List<String> requiredVariables, String requiresArtifactType,
            boolean requiresArtifactApproval, String outputType) {
        public Stage {allowedSkills=copy(allowedSkills);requiredVariables=copy(requiredVariables);}
    }
    public record AcceptanceRule(String type, String artifactType) {}
    private static final Set<String> TYPES=Set.of("SCRIPT","STORYBOARD","PROMPT","IMAGE","VIDEO","AUDIO");
    private static <T>List<T> copy(List<T> list) {return list==null?null:java.util.Collections.unmodifiableList(new java.util.ArrayList<>(list));}

    public void validate() {
        text(instruction,6000,"全局指导"); size(requiredInputs,0,6); size(variables,0,12);
        size(stages,1,8); size(requiredCapabilities,0,16); size(acceptanceRules,0,12);
        Set<String> inputs=new HashSet<>(), vars=new HashSet<>(), stageIds=new HashSet<>(), capabilities=new HashSet<>();
        for(var input:requiredInputs) {
            if(input==null||input.type()==null||!TYPES.contains(input.type())||input.minCount()<1||input.minCount()>12||!inputs.add(input.type())) bad();
        }
        for(var variable:variables) {
            if(variable==null) bad(); id(variable.id()); text(variable.label(),120,"变量名称");
            if(!vars.add(variable.id().toLowerCase(Locale.ROOT))) bad(); size(variable.options(),0,5);
            if(variable.options().size()==1) bad(); Set<String> options=new HashSet<>();
            for(var option:variable.options()) {text(option,120,"选项");if(!options.add(option)) bad();}
        }
        for(String capability:requiredCapabilities) {id(capability);if(!capabilities.add(capability)) bad();}
        Set<String> variableIds=new HashSet<>(); variables.forEach(v->variableIds.add(v.id()));
        Set<String> availableTypes=new HashSet<>(inputs);
        for(var stage:stages) {
            if(stage==null) bad(); id(stage.id()); if(!stageIds.add(stage.id().toLowerCase(Locale.ROOT))) bad();
            text(stage.title(),120,"阶段名称"); text(stage.instruction(),3000,"阶段指导");
            size(stage.allowedSkills(),0,8); size(stage.requiredVariables(),0,12);
            if(new HashSet<>(stage.allowedSkills()).size()!=stage.allowedSkills().size()||new HashSet<>(stage.requiredVariables()).size()!=stage.requiredVariables().size()) bad();
            for(String skill:stage.allowedSkills()) if(!capabilities.contains(skill)) bad();
            for(String variable:stage.requiredVariables()) if(!variableIds.contains(variable)) bad();
            if(stage.requiresArtifactType()!=null&&!TYPES.contains(stage.requiresArtifactType())) bad();
            if(stage.requiresArtifactApproval()&&stage.requiresArtifactType()==null) bad();
            if(stage.requiresArtifactType()!=null&&!availableTypes.contains(stage.requiresArtifactType())) bad();
            if(stage.outputType()!=null&&!TYPES.contains(stage.outputType())) bad();
            if(stage.outputType()==null&&!stage.allowedSkills().isEmpty()) bad();
            if(stage.outputType()!=null&&stage.allowedSkills().isEmpty()) bad();
            if(stage.outputType()!=null) availableTypes.add(stage.outputType());
        }
        for(var rule:acceptanceRules) if(rule==null||rule.artifactType()==null||!"ARTIFACT_EXISTS".equals(rule.type())||!TYPES.contains(rule.artifactType())||!availableTypes.contains(rule.artifactType())) bad();
    }
    private static void size(List<?> values,int min,int max) {if(values==null||values.size()<min||values.size()>max||values.stream().anyMatch(java.util.Objects::isNull)) bad();}
    private static void id(String value) {if(value==null||!value.matches("[A-Za-z0-9_-]{1,64}")) bad();}
    private static void text(String value,int max,String field) {if(value==null||value.isBlank()||value.length()>max) throw BusinessException.badRequest(field+"为空或超出长度限制");}
    private static void bad() {throw BusinessException.badRequest("技能规则不符合支持的有限阶段、变量或能力定义");}
}
