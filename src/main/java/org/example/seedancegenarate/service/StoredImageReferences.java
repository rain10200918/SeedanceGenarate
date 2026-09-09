package org.example.seedancegenarate.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import lombok.RequiredArgsConstructor;
import org.example.seedancegenarate.entity.VideoTask;
import org.example.seedancegenarate.exception.BusinessException;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.*;

/** Internal source identities, not an externally bindable URL protocol. */
@Component
@RequiredArgsConstructor
public class StoredImageReferences {
    private final VideoTaskService tasks;
    private final ContentModerationPolicy moderation;
    private final ArtifactExpiryPolicy expiry;
    private final ArtifactStorage storage;
    public record Reference(String sourceTaskId,String objectKey) {}
    public VideoTask validate(Long owner,Reference reference) {
        if(owner==null || owner<1 || reference==null || reference.sourceTaskId()==null || reference.objectKey()==null)
            throw BusinessException.badRequest("参考图片身份无效");
        VideoTask source=tasks.getOne(Wrappers.<VideoTask>lambdaQuery().eq(VideoTask::getUserId,owner)
                .eq(VideoTask::getBizTaskId,reference.sourceTaskId()),false);
        if(source==null || !owner.equals(source.getUserId()) || !reference.sourceTaskId().equals(source.businessTaskId())
                || !"IMAGE".equals(source.getOutputType()) || !"SUCCESS".equals(source.getStatus())
                || !"OSS".equals(source.getArtifactStorageType()) || reference.objectKey().isBlank()
                || !reference.objectKey().equals(source.getArtifactKey()) || moderation.isBlocked(source) || expiry.isExpired(source))
            throw BusinessException.badRequest("参考图片不可用，请重新选择");
        return source;
    }
    public List<String> signedUrls(Long owner,List<Reference> references) throws Exception {
        if(references==null || references.isEmpty() || references.size()>1) throw BusinessException.badRequest("参考图片数量无效");
        var urls=new ArrayList<String>();
        for(var reference:references) {
            validateAvailable(owner,reference);
            urls.add(storage.createSignedGetUrl(reference.objectKey(),Duration.ofMinutes(15)));
        }
        return List.copyOf(urls);
    }
    public void validateAvailable(Long owner,Reference reference) {
        validate(owner,reference);
        try {
            if(!storage.exists(reference.objectKey()))throw BusinessException.badRequest("参考图片已失效");
        } catch(BusinessException e) {throw e;}
        catch(Exception e) {throw BusinessException.badRequest("暂时无法验证参考图片，请稍后重新准备");}
    }
}
