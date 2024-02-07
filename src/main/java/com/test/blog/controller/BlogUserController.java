package com.test.blog.controller;


import com.test.blog.entity.dto.AddBlogUserModel;
import com.test.blog.entity.dto.ShowBlogUserModel;
import com.test.blog.mapper.BlogUserMapper;
import com.test.blog.service.impl.BlogUserServiceImpl;
import com.test.blog.utils.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

//@Controller
//@RequestMapping("/user")
//@Slf4j
@RequestMapping("/bbs")
public class BlogUserController {
    private static final Logger log = LoggerFactory.getLogger(BlogUserController.class);

    @Autowired
    private BlogUserServiceImpl blogUserService;

    @Autowired
    private BlogUserMapper blogUserMapper;


    @RequestMapping(value = "user", method = RequestMethod.POST)
    public ShowBlogUserModel addTestUserResponse(@RequestBody AddBlogUserModel addBlogUserModel) {
        String user_name = addBlogUserModel.getUserName();
        String user_password = addBlogUserModel.getUserPassword();
        String user_id = StringUtils.createRandomCharData(8);
        String phone_number = addBlogUserModel.getPhoneNumber();
        if (user_name == null || user_password == null || phone_number == null){
//            log.error("Will choose parameters not exist: " + String.format("user_name:%s, user_pass: "));
            return null;
        }

        ShowBlogUserModel showBlogUserModel = null;
        try {
            blogUserService.addBlogUser(user_name, phone_number, phone_number, user_id);
        }catch (Exception e){
            return null;
        }
        try {
            showBlogUserModel = blogUserMapper.selectShowBlogUser(user_id);
        }catch (Exception e){
            return null;
        }
        return showBlogUserModel;

    }
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
