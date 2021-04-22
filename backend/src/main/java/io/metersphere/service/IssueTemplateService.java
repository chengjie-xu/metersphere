package io.metersphere.service;

import io.metersphere.base.domain.*;
import io.metersphere.base.mapper.IssueTemplateMapper;
import io.metersphere.base.mapper.ext.ExtIssueTemplateMapper;
import io.metersphere.commons.constants.TemplateConstants;
import io.metersphere.commons.exception.MSException;
import io.metersphere.commons.utils.BeanUtils;
import io.metersphere.commons.utils.ServiceUtils;
import io.metersphere.controller.request.BaseQueryRequest;
import io.metersphere.controller.request.UpdateIssueTemplateRequest;
import io.metersphere.dto.CustomFieldDao;
import io.metersphere.dto.IssueTemplateDao;
import io.metersphere.i18n.Translator;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.List;
import java.util.UUID;

@Service
@Transactional(rollbackFor = Exception.class)
public class IssueTemplateService extends TemplateBaseService {

    @Resource
    ExtIssueTemplateMapper extIssueTemplateMapper;

    @Resource
    IssueTemplateMapper issueTemplateMapper;

    @Resource
    CustomFieldTemplateService customFieldTemplateService;

    @Resource
    CustomFieldService customFieldService;

    @Resource
    ProjectService projectService;

    public void add(UpdateIssueTemplateRequest request) {
        checkExist(request);
        IssueTemplate template = new IssueTemplate();
        BeanUtils.copyBean(template, request);
        template.setId(UUID.randomUUID().toString());
        template.setCreateTime(System.currentTimeMillis());
        template.setUpdateTime(System.currentTimeMillis());
        if (template.getSystem() == null) {
            template.setSystem(false);
        }
        template.setGlobal(false);
        issueTemplateMapper.insert(template);
        customFieldTemplateService.create(request.getCustomFields(), template.getId(),
                TemplateConstants.FieldTemplateScene.ISSUE.name());
    }

    public List<IssueTemplate> list(BaseQueryRequest request) {
        request.setOrders(ServiceUtils.getDefaultOrder(request.getOrders()));
        return extIssueTemplateMapper.list(request);
    }

    public void delete(String id) {
        checkTemplateUsed(id, projectService::getByIssueTemplateId);
        issueTemplateMapper.deleteByPrimaryKey(id);
        customFieldTemplateService.deleteByTemplateId(id);
    }

    public void update(UpdateIssueTemplateRequest request) {
        if (request.getGlobal() != null && request.getGlobal()) {
            // 如果是全局字段，则创建对应工作空间字段
            add(request);
        } else {
            checkExist(request);
            customFieldTemplateService.deleteByTemplateId(request.getId());
            IssueTemplate template = new IssueTemplate();
            BeanUtils.copyBean(template, request);
            template.setUpdateTime(System.currentTimeMillis());
            issueTemplateMapper.updateByPrimaryKeySelective(template);
            customFieldTemplateService.create(request.getCustomFields(), request.getId(),
                    TemplateConstants.FieldTemplateScene.ISSUE.name());
        }
    }

    /**
     * 获取该工作空间的系统模板
     * - 如果没有，则创建该工作空间模板，并关联默认的字段
     * - 如果有，则更新原来关联的 fieldId
     * @param customField
     */
    public void handleSystemFieldCreate(CustomField customField) {
        IssueTemplate workspaceSystemTemplate = getWorkspaceSystemTemplate(customField.getWorkspaceId());
        if (workspaceSystemTemplate == null) {
            createTemplateWithUpdateField(customField.getWorkspaceId(), customField);
        } else {
            updateRelateWithUpdateField(workspaceSystemTemplate, customField);
        }
    }

    private IssueTemplate getWorkspaceSystemTemplate(String workspaceId) {
        IssueTemplateExample example = new IssueTemplateExample();
        example.createCriteria()
                .andWorkspaceIdEqualTo(workspaceId)
                .andSystemEqualTo(true);
        List<IssueTemplate> issueTemplates = issueTemplateMapper.selectByExampleWithBLOBs(example);
        if (CollectionUtils.isNotEmpty(issueTemplates)) {
            return issueTemplates.get(0);
        }
        return null;
    }

    private void createTemplateWithUpdateField(String workspaceId, CustomField customField) {
        UpdateIssueTemplateRequest request = new UpdateIssueTemplateRequest();
        IssueTemplate issueTemplate = new IssueTemplate();
        issueTemplate.setName("default");
        issueTemplate.setPlatform(TemplateConstants.IssueTemplatePlatform.metersphere.name());
        issueTemplate.setGlobal(false);
        issueTemplate.setSystem(true);
        issueTemplate.setWorkspaceId(workspaceId);
        BeanUtils.copyBean(request, issueTemplate);
        List<CustomFieldTemplate> systemFieldCreateTemplate =
                customFieldTemplateService.getSystemFieldCreateTemplate(customField, TemplateConstants.FieldTemplateScene.ISSUE.name());
        request.setCustomFields(systemFieldCreateTemplate);
        add(request);
    }

    private void updateRelateWithUpdateField(IssueTemplate template, CustomField customField) {
        CustomField globalField = customFieldService.getGlobalFieldByName(customField.getName());
        customFieldTemplateService.updateFieldIdByTemplate(template.getId(), globalField.getId(), customField.getId());
    }

    private void checkExist(IssueTemplate issueTemplate) {
        if (issueTemplate.getName() != null) {
            IssueTemplateExample example = new IssueTemplateExample();
            IssueTemplateExample.Criteria criteria = example.createCriteria();
            criteria.andNameEqualTo(issueTemplate.getName())
                    .andWorkspaceIdEqualTo(issueTemplate.getWorkspaceId());
            if (StringUtils.isNotBlank(issueTemplate.getId())) {
                criteria.andIdNotEqualTo(issueTemplate.getId());
            }
            if (issueTemplateMapper.selectByExample(example).size() > 0) {
                MSException.throwException(Translator.get("template_already") + issueTemplate.getName());
            }
        }
    }

    public IssueTemplate getDefaultTemplate(String workspaceId) {
        IssueTemplateExample example = new IssueTemplateExample();
        example.createCriteria()
                .andWorkspaceIdEqualTo(workspaceId)
                .andSystemEqualTo(true);
        List<IssueTemplate> issueTemplates = issueTemplateMapper.selectByExample(example);
        if (CollectionUtils.isNotEmpty(issueTemplates)) {
            return issueTemplates.get(0);
        } else {
            example.clear();
            example.createCriteria()
                    .andGlobalEqualTo(true);
            return issueTemplateMapper.selectByExample(example).get(0);
        }
    }

    public List<IssueTemplate> getOption(String workspaceId) {
        IssueTemplateExample example = new IssueTemplateExample();
        example.createCriteria()
                .andWorkspaceIdEqualTo(workspaceId)
                .andSystemEqualTo(false);
        List<IssueTemplate> issueTemplates = issueTemplateMapper.selectByExample(example);
        issueTemplates.add(getDefaultTemplate(workspaceId));
        return issueTemplates;
    }

    public IssueTemplateDao getTemplate(String projectId) {
        Project project = projectService.getProjectById(projectId);
        String caseTemplateId = project.getCaseTemplateId();
        IssueTemplate issueTemplate = null;
        IssueTemplateDao issueTemplateDao = new IssueTemplateDao();
        if (StringUtils.isNotBlank(caseTemplateId)) {
            issueTemplate = issueTemplateMapper.selectByPrimaryKey(caseTemplateId);
            if (issueTemplate == null) {
                issueTemplate = getDefaultTemplate(project.getWorkspaceId());
            }
        } else {
            issueTemplate = getDefaultTemplate(project.getWorkspaceId());
        }
        BeanUtils.copyBean(issueTemplateDao, issueTemplate);
        List<CustomFieldDao> result = customFieldService.getCustomFieldByTemplateId(issueTemplate.getId());
        issueTemplateDao.setCustomFields(result);
        return issueTemplateDao;
    }
}
