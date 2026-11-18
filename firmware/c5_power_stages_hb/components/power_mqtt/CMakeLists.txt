set(mqtt_requirement "")
if(IDF_VERSION_MAJOR LESS 6)
    set(mqtt_requirement mqtt)
endif()

idf_component_register(SRCS "power_mqtt.c" "link_probe.c" INCLUDE_DIRS "include"
                       PRIV_REQUIRES esp_event esp_netif esp_timer mbedtls nvs_flash ${mqtt_requirement})
