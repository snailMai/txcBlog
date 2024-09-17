package com.test.blog.testfortestuser.controller;


import com.test.blog.testfortestuser.dao.TestUserMapper;
import com.test.blog.testfortestuser.domain.TestUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


/**
 * @author mai
 */

@RestController
@RequestMapping("/testuser")
@Configuration
public class TestUserController {
    private static final Logger log = LoggerFactory.getLogger(TestUserController.class);
    @Autowired
    TestUserMapper testUserMapper;

    @RequestMapping(value = "pic")
    public TestUser pic() {

        try {
            // 调用dao层
            TestUser testUser = testUserMapper.selectUserByName("wx");
            log.debug("-----------debug----------");
            log.info("-----------pic----------");
            return testUser;
        }catch (Exception e){
            log.error("call error: " + e);
        }

        return null;
    }

    // 后续返回的发展方向
    @RequestMapping(value="/testStatusTwo", method = RequestMethod.GET)
    public ResponseEntity<Map<String, Object>> testHttpStatusTwo(HttpServletResponse response) throws IOException{
        Map<String,Object> map = new HashMap<String,Object>();
        map.put("name", "user");
        return new ResponseEntity<Map<String,Object>>(map, HttpStatus.BAD_REQUEST);
    }
}
