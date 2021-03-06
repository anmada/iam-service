package io.choerodon.iam.domain.service.impl;

import io.choerodon.core.exception.CommonException;
import io.choerodon.core.notify.NoticeSendDTO;
import io.choerodon.iam.domain.iam.entity.UserE;
import io.choerodon.iam.domain.repository.UserRepository;
import io.choerodon.iam.domain.service.IUserService;
import io.choerodon.iam.infra.dataobject.UserDO;
import io.choerodon.iam.infra.feign.NotifyFeignClient;
import io.choerodon.mybatis.service.BaseServiceImpl;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author superlee
 * @data 2018/4/12
 */
@Service
public class IUserServiceImpl extends BaseServiceImpl<UserDO> implements IUserService {

    private UserRepository userRepository;
    private NotifyFeignClient notifyFeignClient;

    public IUserServiceImpl(UserRepository userRepository, NotifyFeignClient notifyFeignClient) {
        this.userRepository = userRepository;
        this.notifyFeignClient = notifyFeignClient;
    }

    @Override
    public UserE updateUserEnabled(Long userId) {
        UserE userE = userRepository.selectByPrimaryKey(userId);
        if (userE == null) {
            throw new CommonException("error.user.not.exist");
        }
        userE.enable();
        return userRepository.updateSelective(userE).hiddenPassword();
    }

    @Override
    public UserE updateUserDisabled(Long userId) {
        UserE userE = userRepository.selectByPrimaryKey(userId);
        if (userE == null) {
            throw new CommonException("error.user.not.exist");
        }
        userE.disable();
        return userRepository.updateSelective(userE).hiddenPassword();
    }


    @Override
    public UserE updateUserInfo(UserE userE) {
        return userRepository.updateUserInfo(userE);
    }

    @Override
    public void sendNotice(Long fromUserId, List<Long> userIds, String code,
                           Map<String, Object> params, Long sourceId) {
        NoticeSendDTO noticeSendDTO = new NoticeSendDTO();
        noticeSendDTO.setCode(code);
        noticeSendDTO.setSourceId(sourceId);
        NoticeSendDTO.User currentUser = new NoticeSendDTO.User();
        currentUser.setId(fromUserId);
        UserE currentUserE = userRepository.selectByPrimaryKey(fromUserId);
        currentUser.setEmail(currentUserE.getEmail());
        currentUser.setLoginName(currentUserE.getLoginName());
        currentUser.setRealName(currentUserE.getRealName());
        noticeSendDTO.setFromUser(currentUser);
        noticeSendDTO.setParams(params);
        List<NoticeSendDTO.User> users = new LinkedList<>();
        userIds.forEach(id -> {
            NoticeSendDTO.User user = new NoticeSendDTO.User();
            user.setId(id);
            UserE userE = userRepository.selectByPrimaryKey(id);
            if (userE != null) {
                //有角色分配，但是角色已经删除
                user.setEmail(userE.getEmail());
                user.setLoginName(userE.getLoginName());
                user.setRealName(userE.getRealName());
                users.add(user);
            }
        });
        noticeSendDTO.setTargetUsers(users);
        notifyFeignClient.postNotice(noticeSendDTO);
    }
}
