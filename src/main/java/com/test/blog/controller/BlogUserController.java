package com.test.blog.controller;


import com.test.blog.entity.dto.BlogUserCreateWrap;
import com.test.blog.entity.dto.BlogUserResponseWrap;
import com.test.blog.mapper.BlogUserMapper;
import com.test.blog.service.impl.BlogUserServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.List;

//@Controller
//@RequestMapping("/user")
//@Slf4j
public class BlogUserController {

    @Autowired
    private BlogUserServiceImpl blogUserService;

    @Autowired
    private BlogUserMapper blogUserMapper;

//    @PostMapping(path = "/", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
//    @ResponseBody
//    @ResponseStatus(HttpStatus.CREATED)
//    public BlogUserResponseWrap addBlogUser (@RequestBody BlogUserCreateWrap req){
//
//        try {
//            blogUserMapper.insert(publicthirdlibs);
//        } catch (Exception ex) {
//            List<String> sss = UtilService.regMatch(ex.getMessage(), "INSERT INTO ([a-zA-Z_]+[a-zA-Z0-9_]?)");
//            if (sss.size() > 0) {
//                List<String> infos = UtilService.regMatch(ex.getMessage(), "'[^\\s]*'");
//
//                ProcessResult p = ProcessResult.conflict(0, infos.get(0) + "重复");
//                return fail(responseErrorService.changeErrorResult(p),
//                        responseErrorService.changeHttpStatus(p));
//            }
//            ProcessResult p = ProcessResult.internalServer(0, sss.get(0));
//            return fail(responseErrorService.changeErrorResult(p),
//                    responseErrorService.changeHttpStatus(p)
//            );
//        }
//        return blogUserService;
//    }


}
