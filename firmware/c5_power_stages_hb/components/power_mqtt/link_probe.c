#include <string.h>

#include "esp_crt_bundle.h"
#include "esp_log.h"
#include "mqtt_client.h"
#include "power_mqtt.h"

static const char *TAG = "link_probe";
static const char ping_topic[] = "c5-link/000000000000/ping";
static const char pong_topic[] = "c5-link/000000000000/pong";

static void on_probe_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    esp_mqtt_event_handle_t event = data;
    if (id == MQTT_EVENT_CONNECTED) {
        esp_mqtt_client_subscribe(event->client, ping_topic, 0);
    } else if (id == MQTT_EVENT_SUBSCRIBED) {
        ESP_LOGI(TAG, "Phone MQTT ready (ping/pong)");
    } else if (id == MQTT_EVENT_DATA &&
               !event->retain && event->current_data_offset == 0 &&
               event->data_len == 16 && event->data_len == event->total_data_len &&
               event->topic && event->data &&
               event->topic_len == sizeof(ping_topic) - 1 &&
               memcmp(event->topic, ping_topic, sizeof(ping_topic) - 1) == 0) {
        for (int i = 0; i < event->data_len; i++) {
            char c = event->data[i];
            if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')) {
                return;
            }
        }
        if (esp_mqtt_client_enqueue(event->client, pong_topic, event->data, 16, 0, 0, true) >= 0) {
            ESP_LOGI(TAG, "Phone MQTT: ping received, pong queued");
        }
    }
}

esp_err_t power_link_probe_start(void)
{
    const esp_mqtt_client_config_t config = {
        .broker.address.uri = "mqtts://test.mosquitto.org:8886",
        .broker.verification.crt_bundle_attach = esp_crt_bundle_attach,
        .session.protocol_ver = MQTT_PROTOCOL_V_3_1_1,
    };
    esp_mqtt_client_handle_t client = esp_mqtt_client_init(&config);
    if (client == NULL) {
        return ESP_ERR_NO_MEM;
    }
    esp_err_t err = esp_mqtt_client_register_event(client, ESP_EVENT_ANY_ID, on_probe_event, NULL);
    if (err == ESP_OK) {
        err = esp_mqtt_client_start(client);
    }
    if (err != ESP_OK) {
        esp_mqtt_client_destroy(client);
    }
    return err;
}
