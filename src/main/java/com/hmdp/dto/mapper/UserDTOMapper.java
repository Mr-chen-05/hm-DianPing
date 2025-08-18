package com.hmdp.dto.mapper;

import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface UserDTOMapper {
    UserDTO toUserDTO(User user);
}
