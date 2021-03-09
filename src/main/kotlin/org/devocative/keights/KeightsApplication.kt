package org.devocative.keights

import io.kubernetes.client.openapi.Configuration
import io.kubernetes.client.openapi.apis.CoreV1Api
import io.kubernetes.client.util.ClientBuilder
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.context.annotation.Bean
import org.springframework.scheduling.annotation.EnableScheduling
import java.io.IOException
import java.util.concurrent.TimeUnit

@EnableScheduling
@SpringBootApplication
class KeightsApplication {

    @get:Bean
    val coreV1Api: CoreV1Api
        get() = try {
            val client = ClientBuilder.defaultClient()
            val httpClient = client
                .httpClient
                .newBuilder()
                .readTimeout(0, TimeUnit.SECONDS)
                .build()
            client.httpClient = httpClient
            client.isVerifyingSsl = false
            Configuration.setDefaultApiClient(client)
            CoreV1Api(client)
        } catch (e: IOException) {
            throw KeightsException(e)
        }

    /*
    TIP: converted by Intellij Idea
    companion object {
        // ------------------------------
        @JvmStatic
        fun main(args: Array<String>) {
            SpringApplication.run(KeightsApplication::class.java, *args)
        }
    }
    */
}

fun main(args: Array<String>) {
    //TIP: java'ish approach
    //SpringApplication.run(KeightsApplication::class.java, *args)

    //TIP: kolin'ish approach
    runApplication<KeightsApplication>(*args)
}