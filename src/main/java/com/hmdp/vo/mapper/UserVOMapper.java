package com.hmdp.vo.mapper;

import com.hmdp.dto.UserDTO;
import com.hmdp.vo.UserVO;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserVOMapper {
    UserVO toUserVO(UserDTO userDTO);

}
