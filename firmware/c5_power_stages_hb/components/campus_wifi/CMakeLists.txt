idf_component_register(SRCS "campus_wifi.c" INCLUDE_DIRS "include"
                     PRIV_REQUIRES esp_event esp_netif esp_wifi wpa_supplicant)
if(EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/campus_ca.pem")
    target_add_binary_data(${COMPONENT_LIB} "campus_ca.pem" TEXT)
    target_compile_definitions(${COMPONENT_LIB} PRIVATE CAMPUS_WIFI_HAS_CA=1)
endif()
