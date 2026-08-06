package org.example.seedancegenarate.entity;

import lombok.Data;


@Data
public class Result<T> {


    /**
     * 状态码
     */
    private Integer code;


    /**
     * 提示信息
     */
    private String message;


    /**
     * 数据
     */
    private T data;



    public Result(){

    }



    public Result(
            Integer code,
            String message,
            T data
    ){

        this.code = code;
        this.message = message;
        this.data = data;

    }



    public static <T> Result<T> success(T data){

        return new Result<>(
                200,
                "success",
                data
        );

    }



    public static <T> Result<T> fail(String message){

        return new Result<>(
                500,
                message,
                null
        );

    }



    public static <T> Result<T> unauthorized(String message){

        return new Result<>(
                401,
                message,
                null
        );

    }



    public static <T> Result<T> tooManyRequests(String message){

        return new Result<>(
                429,
                message,
                null
        );

    }
}
