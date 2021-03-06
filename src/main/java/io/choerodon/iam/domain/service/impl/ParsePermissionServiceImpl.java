package io.choerodon.iam.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.choerodon.core.exception.CommonException;
import io.choerodon.core.iam.InitRoleCode;
import io.choerodon.core.iam.ResourceLevel;
import io.choerodon.core.swagger.PermissionData;
import io.choerodon.core.swagger.SwaggerExtraData;
import io.choerodon.eureka.event.EurekaEventPayload;
import io.choerodon.iam.domain.iam.entity.PermissionE;
import io.choerodon.iam.domain.iam.entity.RolePermissionE;
import io.choerodon.iam.domain.repository.PermissionRepository;
import io.choerodon.iam.domain.repository.RolePermissionRepository;
import io.choerodon.iam.domain.repository.RoleRepository;
import io.choerodon.iam.domain.service.ParsePermissionService;
import io.choerodon.iam.infra.dataobject.PermissionDO;
import io.choerodon.iam.infra.dataobject.RoleDO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.util.*;

/**
 * @author zhipeng.zuo
 * @author superlee
 */
@Service
public class ParsePermissionServiceImpl implements ParsePermissionService {

    private static final Logger logger = LoggerFactory.getLogger(ParsePermissionService.class);

    private final ObjectMapper objectMapper = new ObjectMapper();

    private PermissionRepository permissionRepository;

    private RolePermissionRepository rolePermissionRepository;

    private RestTemplate restTemplate = new RestTemplate();

    private RoleRepository roleRepository;

    @Value("${choerodon.cleanRolePermission:true}")
    private boolean cleanErrorRolePermission;

    public void setRestTemplate(RestTemplate restTemplate) {
        this.restTemplate = restTemplate;
    }

    public ParsePermissionServiceImpl(PermissionRepository permissionRepository,
                                      RolePermissionRepository rolePermissionRepository,
                                      RoleRepository roleRepository) {
        this.permissionRepository = permissionRepository;
        this.rolePermissionRepository = rolePermissionRepository;
        this.roleRepository = roleRepository;
    }

    private void fetchSwaggerJsonByIp(final EurekaEventPayload payload) {
        ResponseEntity<String> response = restTemplate.getForEntity("http://" + payload.getInstanceAddress() + "/v2/choerodon/api-docs",
                String.class);
        if (response.getStatusCode() == HttpStatus.OK) {
            payload.setApiData(response.getBody());
        } else {
            throw new CommonException("fetch swagger error, statusCode is not 2XX, serviceId: " + payload.getId());
        }

    }

    @Override
    public void parser(EurekaEventPayload payload) {
        //清理role_permission表层级不符的脏数据，会导致基于角色创建失败
        if (cleanErrorRolePermission) {
            logger.info("begin to clean the error role_permission");
            cleanRolePermission();
        }
        try {
            fetchSwaggerJsonByIp(payload);
            String serviceName = payload.getAppName();
            logger.info("receive message from manager-service, service: {}, version: {}, ip: {}",
                    serviceName, payload.getVersion(), payload.getInstanceAddress());
            String json = payload.getApiData();
            if (!StringUtils.isEmpty(serviceName) && !StringUtils.isEmpty(json)) {
                JsonNode node = objectMapper.readTree(json);
                Iterator<Map.Entry<String, JsonNode>> pathIterator = node.get("paths").fields();
                Map<String, RoleDO> initRoleMap = queryInitRoleByCode();
                while (pathIterator.hasNext()) {
                    Map.Entry<String, JsonNode> pathNode = pathIterator.next();
                    Iterator<Map.Entry<String, JsonNode>> methodIterator = pathNode.getValue().fields();
                    parserMethod(methodIterator, pathNode, serviceName, initRoleMap);
                }
            }
        } catch (IOException e) {
            throw new CommonException("error.parsePermissionService.parse.IOException", e);
        }
    }

    private void cleanRolePermission() {
        List<RoleDO> roles = roleRepository.selectAll();
        int count = 0;
        for (RoleDO role : roles) {
            List<PermissionDO> permissions = permissionRepository.selectErrorLevelPermissionByRole(role);
            for (PermissionDO permission : permissions) {
                RolePermissionE rp = new RolePermissionE(null, role.getId(), permission.getId());
                rolePermissionRepository.delete(rp);
                logger.debug("delete error role_permission, role id: {}, code: {}, level: {} ## permission id: {}, code:{}, level: {}",
                        role.getId(), role.getCode(), role.getLevel(), permission.getId(), permission.getCode(), permission.getLevel());
                count++;
            }
        }
        logger.info("clean error role_permission finished, total: {}", count);
    }

    /**
     * 解析文档树某个路径的所有方法
     *
     * @param methodIterator 所有方法
     * @param pathNode       路径
     * @param serviceName    服务名
     */
    private void parserMethod(Iterator<Map.Entry<String, JsonNode>> methodIterator,
                              Map.Entry<String, JsonNode> pathNode, String serviceName,
                              Map<String, RoleDO> initRoleMap) {
        while (methodIterator.hasNext()) {
            Map.Entry<String, JsonNode> methodNode = methodIterator.next();
            JsonNode tags = methodNode.getValue().get("tags");
            SwaggerExtraData extraData = null;
            String resourceCode = null;
            boolean illegal = true;
            List<String> illegalTags = new ArrayList<>();
            for (int i = 0; i < tags.size(); i++) {
                String tag = tags.get(i).asText();
                //添加choerodon-eureka例外的以-endpoint结尾的tag，
                if (tag.endsWith("-controller")) {
                    illegal = false;
                    resourceCode = tag.substring(0, tag.length() - "-controller".length());
                } else if (tag.endsWith("-endpoint")) {
                    illegal = false;
                    resourceCode = tag.substring(0, tag.length() - "-endpoint".length());
                }
                else {
                    illegalTags.add(tag);
                }
            }
            if(logger.isDebugEnabled() && illegal) {
                logger.debug("skip the controller/endpoint because of the illegal tags {}, please ensure the controller is end with ##Controller## or ##EndPoint##", illegalTags);
            }
            try {
                JsonNode extraDataNode = methodNode.getValue().get("description");
                if (extraDataNode == null) {
                    continue;
                }
                extraData = objectMapper.readValue(extraDataNode.asText(), SwaggerExtraData.class);
            } catch (IOException e) {
                logger.info("extraData read failed.", e);
            }
            if (extraData == null || resourceCode == null) {
                continue;
            }
            String description = methodNode.getValue().get("summary").asText();
            String[] roles = null;
            if (extraData.getPermission() != null) {
                roles = extraData.getPermission().getRoles();
            }
            processPermission(extraData, pathNode.getKey(),
                    methodNode.getKey(), serviceName, resourceCode, description, initRoleMap, roles);

        }
    }

    /**
     * 关于permission目前只有插入和更新操作，没有删除废弃的permission。因为目前的从swagger拿到的permission json无法判断是否与数据库中已存在的permission一致
     * 后续如果想通过parse的方式删除废弃的permission，目前的想法是只能在每个接口上加一个不变且各不相同的唯一标识，通过标识判断到底是删除了接口还是更新了接口
     */
    private void processPermission(SwaggerExtraData extraData, String path, String method,
                                   String serviceName, String resourceCode, String description,
                                   Map<String, RoleDO> initRoleMap, String[] roles) {
        PermissionData permission = extraData.getPermission();
        String action = permission.getAction();
        String code = serviceName + "." + resourceCode + "." + action;
        PermissionE permissionE = permissionRepository.selectByCode(code);
        if (permissionE == null) {
            //插入操作
            PermissionE p =
                    new PermissionE(code, path, method, permission.getPermissionLevel(), description, action,
                            resourceCode, permission.isPermissionPublic(), permission.isPermissionLogin(), permission.isPermissionWithin(), serviceName, null);
            PermissionE permissionE1 = permissionRepository.insertSelective(p);
            if (permissionE1 != null) {
                insertRolePermission(permissionE1, initRoleMap, roles);
                logger.debug("url: {} method: {} permission: {}",
                        path,
                        method,
                        permissionE1.getCode());
            }
        } else {
            //更新操作
            PermissionE p =
                    new PermissionE(code, path, method, permission.getPermissionLevel(), description, action,
                            resourceCode, permission.isPermissionPublic(), permission.isPermissionLogin(), permission.isPermissionWithin(), serviceName, permissionE.getObjectVersionNumber());
            p.setId(permissionE.getId());
            if (!permissionE.equals(p)) {
                permissionRepository.updateSelective(p);
            }
            updateRolePermission(p, initRoleMap, roles);
            logger.debug("url: {} method: {} permission: {}",
                    path,
                    method,
                    p.getCode());
        }
    }

    private void updateRolePermission(PermissionE permission, Map<String, RoleDO> initRoleMap, String[] roles) {
        Long permissionId = permission.getId();
        String level = permission.getLevel();
        RoleDO role = getRoleByLevel(initRoleMap, level);
        if (role != null) {
            RolePermissionE rp = new RolePermissionE(null, role.getId(), permissionId);
            if (rolePermissionRepository.selectOne(rp) == null) {
                rolePermissionRepository.insert(rp);
            }
        }
        List<RoleDO> roleList = roleRepository.selectInitRolesByPermissionId(permissionId);
        //删掉除去SITE_ADMINISTRATOR，ORGANIZATION_ADMINISTRATOR，PROJECT_ADMINISTRATOR的所有role_permission关系
        for (RoleDO roleDO : roleList) {
            String code = roleDO.getCode();
            if (!InitRoleCode.SITE_ADMINISTRATOR.equals(code)
                    && !InitRoleCode.PROJECT_ADMINISTRATOR.equals(code)
                    && !InitRoleCode.ORGANIZATION_ADMINISTRATOR.equals(code)) {
                RolePermissionE rolePermission = new RolePermissionE(null, roleDO.getId(), permissionId);
                rolePermissionRepository.delete(rolePermission);
            }
        }
        if (roles != null) {
            processRolePermission(initRoleMap, roles, permissionId, level);
        }
    }

    /**
     * 先根据permission level关联相应层级的管理员角色
     * level=site -> SITE_ADMINISTRATOR
     * level=organization -> ORGANIZATION_ADMINISTRATOR
     * level=project -> PROJECT_ADMINISTRATOR
     */
    private void insertRolePermission(PermissionE permission, Map<String, RoleDO> initRoleMap, String[] roles) {
        Long permissionId = permission.getId();
        String level = permission.getLevel();
        RoleDO role = getRoleByLevel(initRoleMap, level);
        if (role != null) {
            rolePermissionRepository.insert(new RolePermissionE(null, role.getId(), permissionId));
        }
        //roles不为空，关联自定义角色
        if (roles != null) {
            processRolePermission(initRoleMap, roles, permissionId, level);
        }
    }

    private void processRolePermission(Map<String, RoleDO> initRoleMap, String[] roles, Long permissionId, String level) {
        Set<String> roleSet = new HashSet<>(Arrays.asList(roles));
        for (String roleCode : roleSet) {
            RoleDO role = initRoleMap.get(roleCode);
            if (role == null) {
                //找不到code，说明没有初始化进去角色或者角色code拼错了
                logger.info("can not find the role, role code is : {}", roleCode);
            } else {
                if (level.equals(role.getLevel())) {
                    RolePermissionE rp = new RolePermissionE(null, role.getId(), permissionId);
                    if (rolePermissionRepository.selectOne(rp) == null) {
                        rolePermissionRepository.insert(rp);
                    }
                } else {
                    logger.info("init role level do not match the permission level, permission id: {}, level: {}, @@ role code: {}, level: {}",
                            permissionId, level, role.getCode(), role.getLevel());
                }
            }
        }
    }

    private RoleDO getRoleByLevel(Map<String, RoleDO> initRoleMap, String level) {
        if (ResourceLevel.SITE.value().equals(level)) {
            return initRoleMap.get(InitRoleCode.SITE_ADMINISTRATOR);
        }
        if (ResourceLevel.ORGANIZATION.value().equals(level)) {
            return initRoleMap.get(InitRoleCode.ORGANIZATION_ADMINISTRATOR);
        }
        if (ResourceLevel.PROJECT.value().equals(level)) {
            return initRoleMap.get(InitRoleCode.PROJECT_ADMINISTRATOR);
        }
        return null;
    }

    private Map<String, RoleDO> queryInitRoleByCode() {
        Map<String, RoleDO> map = new HashMap<>(10);
        String[] codes = InitRoleCode.values();
        for (String code : codes) {
            RoleDO role = roleRepository.selectByCode(code);
            if (role == null) {
                logger.info("init roles do not exist, code: {}", code);
            }
            map.put(code, role);
        }
        return map;
    }

}
