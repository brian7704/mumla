package se.lublin.mumla.location;

import java.util.ArrayList;

import retrofit2.Call;
import retrofit2.http.Field;
import retrofit2.http.FormUrlEncoded;
import retrofit2.http.GET;
import retrofit2.http.POST;
import retrofit2.http.Query;

public interface TraccarInterface {
    @GET("/")
    Call<Void> osmAnd(@Query("id") String id, @Query("timestamp") long timestamp, @Query("lat") double lat,
                        @Query("lon") double lon, @Query("speed") float speed, @Query("altitude") double altitude,
                        @Query("bearing") float bearing, @Query("accuracy") float accuracy, @Query("charge") boolean charge,
                        @Query("batt") float batt);

    @FormUrlEncoded
    @POST("/api/session")
    Call<Void> login(@Field("email") String email, @Field("password") String password);

    @GET("/api/devices")
    Call<ArrayList<Device>> devices();

    @GET("/api/positions")
    Call<ArrayList<Position>> positions(@Query("id") int id);
}
