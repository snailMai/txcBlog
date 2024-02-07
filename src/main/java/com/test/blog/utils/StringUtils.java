package com.test.blog.utils;

import java.util.Random;

public class StringUtils {
    //根据指定长度生成字母和数字的随机数
    //0~9的ASCII为48~57
    //A~Z的ASCII为65~90
    //a~z的ASCII为97~122
    public static String createRandomCharData(int length)
    {
        StringBuilder sb=new StringBuilder();
        //随机用以下三个随机生成器
        Random rand=new Random();
        Random randdata=new Random();
        int data=0;
        for(int i=0;i<length;i++)
        {
            int index=rand.nextInt(2);
            //目的是随机选择生成数字，大小写字母

            switch(index)
            {
                case 0:
                    //仅仅会生成0~9
                    data=randdata.nextInt(10);
                    sb.append(data);
                    break;
//                case 1:
//                    //保证只会产生65~90之间的整数
//                    data=randdata.nextInt(26)+65;
//                    sb.append((char)data);
//                    break;
                case 1:
                    //保证只会产生97~122之间的整数
                    data=randdata.nextInt(26)+97;
                    sb.append((char)data);
                    break;
                default:
                    data=randdata.nextInt(26)+97;
                    break;
            }
        }
        String result=sb.toString();
        return result;
    }
}
