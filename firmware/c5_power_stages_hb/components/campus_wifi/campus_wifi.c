#include <stdint.h>
#include <string.h>

#include "freertos/FreeRTOS.h"
#include "freertos/event_groups.h"
#include "freertos/task.h"

#include "esp_eap_client.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif.h"
#include "esp_wifi.h"
#include "campus_wifi.h"

static const char *TAG = "campus_wifi";
static const TickType_t retry_delay = pdMS_TO_TICKS(15000);
static const EventBits_t wifi_disconnected = BIT0;
static const EventBits_t wifi_got_ip = BIT1;
static EventGroupHandle_t wifi_events;

_Static_assert(sizeof(CONFIG_CAMPUS_WIFI_SSID) > 1 &&
               sizeof(CONFIG_CAMPUS_WIFI_SSID) <= 33,
               "Campus Wi-Fi SSID must be 1 to 32 bytes");

#ifdef CAMPUS_WIFI_HAS_CA
extern const uint8_t campus_ca_start[] asm("_binary_campus_ca_pem_start");
extern const uint8_t campus_ca_end[] asm("_binary_campus_ca_pem_end");
#endif

static void on_network_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    if (base == WIFI_EVENT && id == WIFI_EVENT_STA_DISCONNECTED) {
        const wifi_event_sta_disconnected_t *disconnected = data;
        ESP_LOGW(TAG, "Wi-Fi disconnected (reason=%u); retry in 15 s",
                 (unsigned)disconnected->reason);
        xEventGroupSetBits(wifi_events, wifi_disconnected);
    } else if (base == IP_EVENT && id == IP_EVENT_STA_GOT_IP) {
        const ip_event_got_ip_t *got_ip = data;
        ESP_LOGI(TAG, "Campus Wi-Fi ready: IPv4=" IPSTR, IP2STR(&got_ip->ip_info.ip));
        xEventGroupSetBits(wifi_events, wifi_got_ip);
    }
}

static void scan_campus_wifi(void)
{
    wifi_scan_config_t scan = { .ssid = (uint8_t *)CONFIG_CAMPUS_WIFI_SSID };
    esp_err_t err = esp_wifi_scan_start(&scan, true);
    if (err != ESP_OK) {
        ESP_LOGW(TAG, "Wi-Fi scan failed: %s", esp_err_to_name(err));
        return;
    }

    uint16_t total = 0;
    static wifi_ap_record_t records[16];
    uint16_t count = sizeof(records) / sizeof(records[0]);
    ESP_ERROR_CHECK(esp_wifi_scan_get_ap_num(&total));
    ESP_ERROR_CHECK(esp_wifi_scan_get_ap_records(&count, records));
    ESP_LOGI(TAG, "Scan found %u AP(s) for %s; showing %u",
             (unsigned)total, CONFIG_CAMPUS_WIFI_SSID, (unsigned)count);

    for (uint16_t index = 0; index < count; ++index) {
        const wifi_ap_record_t *ap = &records[index];
        ESP_LOGI(TAG, "AP: %s, channel %u, RSSI %d dBm",
                 ap->primary <= 14 ? "2.4 GHz" : "5 GHz",
                 (unsigned)ap->primary, ap->rssi);
    }
}

#ifndef CONFIG_CAMPUS_WIFI_OPEN
static void configure_peap(void)
{
    const char *identity = CONFIG_CAMPUS_WIFI_OUTER_IDENTITY;
    if (identity[0] == '\0') {
        identity = CONFIG_CAMPUS_WIFI_USERNAME;
    }

    ESP_ERROR_CHECK(esp_eap_client_set_identity((const uint8_t *)identity,
                                                 strlen(identity)));
    ESP_ERROR_CHECK(esp_eap_client_set_username((const uint8_t *)CONFIG_CAMPUS_WIFI_USERNAME,
                                                 strlen(CONFIG_CAMPUS_WIFI_USERNAME)));
    ESP_ERROR_CHECK(esp_eap_client_set_password((const uint8_t *)CONFIG_CAMPUS_WIFI_PASSWORD,
                                                 strlen(CONFIG_CAMPUS_WIFI_PASSWORD)));
#ifdef CAMPUS_WIFI_HAS_CA
    ESP_ERROR_CHECK(esp_eap_client_set_ca_cert(campus_ca_start, campus_ca_end - campus_ca_start));
#else
    ESP_ERROR_CHECK(esp_eap_client_use_default_cert_bundle(true));
#endif
    ESP_ERROR_CHECK(esp_eap_client_set_domain_name(CONFIG_CAMPUS_WIFI_RADIUS_DOMAIN));
    ESP_ERROR_CHECK(esp_eap_client_set_eap_methods(ESP_EAP_TYPE_PEAP));
    ESP_ERROR_CHECK(esp_wifi_sta_enterprise_enable());
}
#endif

static void wifi_connect_task(void *arg)
{
    while (true) {
        ESP_LOGI(TAG, "Connecting to %s", CONFIG_CAMPUS_WIFI_SSID);
        esp_err_t err = esp_wifi_connect();
        if (err == ESP_OK) {
            xEventGroupWaitBits(wifi_events, wifi_disconnected, pdTRUE, pdFALSE, portMAX_DELAY);
        } else {
            ESP_LOGW(TAG, "Could not start Wi-Fi connection: %s; retry in 15 s",
                     esp_err_to_name(err));
        }
        vTaskDelay(retry_delay);
    }
}

bool campus_wifi_start(void)
{
    ESP_ERROR_CHECK(esp_netif_init());
    ESP_ERROR_CHECK(esp_event_loop_create_default());
    ESP_ERROR_CHECK(esp_netif_create_default_wifi_sta() ? ESP_OK : ESP_FAIL);

    wifi_init_config_t config = WIFI_INIT_CONFIG_DEFAULT();
    ESP_ERROR_CHECK(esp_wifi_init(&config));
    ESP_ERROR_CHECK(esp_wifi_set_storage(WIFI_STORAGE_RAM));
    ESP_ERROR_CHECK(esp_wifi_set_mode(WIFI_MODE_STA));
    ESP_ERROR_CHECK(esp_wifi_start());
    ESP_ERROR_CHECK(esp_wifi_set_band_mode(WIFI_BAND_MODE_AUTO));
    ESP_ERROR_CHECK(esp_wifi_set_ps(WIFI_PS_MIN_MODEM));
    scan_campus_wifi();

    wifi_config_t station = {
        .sta = {
            .ssid = CONFIG_CAMPUS_WIFI_SSID,
            .scan_method = WIFI_ALL_CHANNEL_SCAN,
            .sort_method = WIFI_CONNECT_AP_BY_SIGNAL,
            .threshold.authmode = WIFI_AUTH_WPA2_ENTERPRISE,
        },
    };
#ifdef CONFIG_CAMPUS_WIFI_OPEN
    station.sta.threshold.authmode = WIFI_AUTH_OPEN;
#else
    if (CONFIG_CAMPUS_WIFI_USERNAME[0] == '\0' || CONFIG_CAMPUS_WIFI_PASSWORD[0] == '\0' ||
        CONFIG_CAMPUS_WIFI_RADIUS_DOMAIN[0] == '\0') {
        ESP_LOGW(TAG, "Scan-only: configure username, password and RADIUS domain in menuconfig");
        return false;
    }
#endif
    ESP_ERROR_CHECK(esp_wifi_set_config(WIFI_IF_STA, &station));

    wifi_events = xEventGroupCreate();
    ESP_ERROR_CHECK(wifi_events ? ESP_OK : ESP_ERR_NO_MEM);
    ESP_ERROR_CHECK(esp_event_handler_register(WIFI_EVENT, WIFI_EVENT_STA_DISCONNECTED,
                                               on_network_event, NULL));
    ESP_ERROR_CHECK(esp_event_handler_register(IP_EVENT, IP_EVENT_STA_GOT_IP,
                                               on_network_event, NULL));
#ifndef CONFIG_CAMPUS_WIFI_OPEN
    configure_peap();
#endif
    BaseType_t result = xTaskCreate(wifi_connect_task, "wifi_connect", 3072, NULL, 5, NULL);
    ESP_ERROR_CHECK(result == pdPASS ? ESP_OK : ESP_ERR_NO_MEM);
    xEventGroupWaitBits(wifi_events, wifi_got_ip, pdFALSE, pdFALSE, portMAX_DELAY);
    return true;
}
