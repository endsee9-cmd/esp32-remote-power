#include <inttypes.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <time.h>

#include "freertos/FreeRTOS.h"
#include "freertos/queue.h"
#include "freertos/task.h"

#include "esp_crt_bundle.h"
#include "esp_event.h"
#include "esp_log.h"
#include "esp_netif_sntp.h"
#include "esp_timer.h"
#include "mqtt_client.h"
#include "nvs.h"
#include "power_mqtt.h"

static const char *TAG = "power_mqtt";
static const char command_prefix[] = "press:";
static const char nvs_timestamp_key[] = "last_press";
static const unsigned timestamp_digits = 10;
static const uint64_t command_max_age = 20;
static const uint64_t command_future_tolerance = 3;
static const TickType_t cooldown = pdMS_TO_TICKS(5000);
/* 心跳周期 30 秒;手机端按「90 秒内收到 online/beat 即在线」判定。 */
static const uint64_t heartbeat_period_us = 30ULL * 1000000ULL;

static esp_mqtt_client_handle_t client;
static QueueHandle_t commands;
static nvs_handle_t nvs;
static esp_err_t (*run_action)(unsigned down_degrees, unsigned return_degrees);
static uint64_t last_accepted;
static char command_topic[160];
static char status_topic[160];
static esp_timer_handle_t heartbeat_timer;

typedef struct {
    uint64_t timestamp;
    unsigned down_degrees;
    unsigned return_degrees;
} press_command_t;

/* 周期心跳:发布 beat:<10位Unix秒> 到状态主题(QoS0、不保留)。
   手机端据此把「设备在线」与「手机连上 Broker」区分开。 */
static void heartbeat(void *arg)
{
    const uint64_t now = (uint64_t)time(NULL);
    char beat[24];
    snprintf(beat, sizeof(beat), "beat:%010" PRIu64, now);
    if (esp_mqtt_client_publish(client, status_topic, beat, (int)strlen(beat), 0, 0) < 0) {
        ESP_LOGW(TAG, "Heartbeat not sent");
    }
}

static void status(const char *message)
{
    if (esp_mqtt_client_enqueue(client, status_topic, message, 0, 1, 0, false) < 0) {
        ESP_LOGW(TAG, "Status not sent: %s", message);
    }
}

static bool parse_angle(const char **cursor, const char *end, unsigned *degrees)
{
    const char *start = *cursor;
    if (start == end || *start != ':') {
        return false;
    }
    const char *digit = start + 1;
    unsigned value = 0;
    while (digit < end && *digit >= '0' && *digit <= '9') {
        if (digit - start > 3) {
            return false;
        }
        value = value * 10 + (unsigned)(*digit - '0');
        ++digit;
    }
    if (value < 5 || value > 180) {
        return false;
    }
    *degrees = value;
    *cursor = digit;
    return true;
}

static bool parse_command(const esp_mqtt_event_t *event, press_command_t *command)
{
    const size_t prefix_len = sizeof(command_prefix) - 1;
    const size_t topic_len = strlen(command_topic);
    const int legacy_length = (int)(prefix_len + timestamp_digits);
    if (event->retain || event->current_data_offset != 0 ||
        event->data_len < legacy_length || event->data_len > legacy_length + 8 ||
        event->data_len != event->total_data_len ||
        event->topic_len != (int)topic_len || !event->topic || !event->data ||
        memcmp(event->topic, command_topic, topic_len) != 0 ||
        memcmp(event->data, command_prefix, prefix_len) != 0) {
        return false;
    }

    uint64_t value = 0;
    for (unsigned index = 0; index < timestamp_digits; ++index) {
        char digit = event->data[prefix_len + index];
        if (digit < '0' || digit > '9') {
            return false;
        }
        value = value * 10 + (unsigned)(digit - '0');
    }
    command->timestamp = value;
    command->down_degrees = 0;
    command->return_degrees = 0;
    const char *cursor = event->data + legacy_length;
    const char *end = event->data + event->data_len;
    if (cursor == end) {
        return true;
    }
    if (!parse_angle(&cursor, end, &command->down_degrees)) {
        return false;
    }
    if (cursor == end) {
        command->return_degrees = command->down_degrees;
        return true;
    }
    return parse_angle(&cursor, end, &command->return_degrees) && cursor == end;
}

static void on_mqtt_event(void *arg, esp_event_base_t base, int32_t id, void *data)
{
    const esp_mqtt_event_t *event = data;
    switch (id) {
    case MQTT_EVENT_CONNECTED:
        if (esp_mqtt_client_subscribe(client, command_topic, 1) < 0) {
            ESP_LOGE(TAG, "Cannot send subscription request");
        }
        break;
    case MQTT_EVENT_SUBSCRIBED:
        ESP_LOGI(TAG, "MQTT subscription acknowledged: %s", command_topic);
        status("online");
        break;
    case MQTT_EVENT_DISCONNECTED:
        ESP_LOGW(TAG, "MQTT disconnected; client will retry");
        break;
    case MQTT_EVENT_ERROR:
        if (event->error_handle) {
            ESP_LOGW(TAG, "MQTT error type=%d, TLS error=%s, broker code=%d",
                     (int)event->error_handle->error_type,
                     esp_err_to_name(event->error_handle->esp_tls_last_esp_err),
                     (int)event->error_handle->connect_return_code);
        }
        break;
    case MQTT_EVENT_DATA: {
        press_command_t command;
        if (!parse_command(event, &command)) {
            ESP_LOGW(TAG, "Discarded retained, fragmented or malformed command");
        } else if (xQueueSend(commands, &command, 0) != pdPASS) {
            ESP_LOGW(TAG, "Command queue full; discarded press request");
        }
        break;
    }
    default:
        break;
    }
}

static void command_worker(void *arg)
{
    TickType_t last_action_tick = 0;
    bool has_acted = false;
    for (;;) {
        press_command_t command;
        if (xQueueReceive(commands, &command, portMAX_DELAY) != pdPASS) {
            continue;
        }

        const uint64_t timestamp = command.timestamp;
        const uint64_t now = (uint64_t)time(NULL);
        if (timestamp <= last_accepted || timestamp + command_max_age < now ||
            timestamp > now + command_future_tolerance) {
            ESP_LOGW(TAG, "Rejected duplicate or out-of-window command: %" PRIu64, timestamp);
            continue;
        }
        TickType_t tick = xTaskGetTickCount();
        if (has_acted && (TickType_t)(tick - last_action_tick) < cooldown) {
            ESP_LOGW(TAG, "Rejected command during 5 s cooldown");
            continue;
        }

        /* Commit before acting, so a retried command cannot press again after a reboot. */
        esp_err_t err = nvs_set_u64(nvs, nvs_timestamp_key, timestamp);
        if (err == ESP_OK) {
            err = nvs_commit(nvs);
        }
        if (err != ESP_OK) {
            ESP_LOGE(TAG, "Command skipped: unable to save timestamp (%s)", esp_err_to_name(err));
            continue;
        }
        last_accepted = timestamp;
        last_action_tick = tick;
        has_acted = true;

        err = run_action(command.down_degrees, command.return_degrees);
        char result[48];
        snprintf(result, sizeof(result), "%s:%" PRIu64, err == ESP_OK ? "done" : "failed", timestamp);
        ESP_LOGI(TAG, "%s", result);
        status(result);
    }
}

esp_err_t power_mqtt_start(esp_err_t (*action)(unsigned down_degrees, unsigned return_degrees))
{
    const char *uri = CONFIG_POWER_MQTT_URI;
    const char *prefix = CONFIG_POWER_MQTT_TOPIC_BASE;
    size_t prefix_len = strlen(prefix);
    if (!action || strncmp(uri, "mqtts://", 8) != 0 || uri[8] == '\0' ||
        CONFIG_POWER_MQTT_USERNAME[0] == '\0' || CONFIG_POWER_MQTT_PASSWORD[0] == '\0' ||
        prefix_len == 0 || prefix_len + sizeof("/status") > sizeof(status_topic) ||
        prefix[prefix_len - 1] == '/' || strpbrk(prefix, "+#") != NULL ||
        CONFIG_POWER_MQTT_SNTP_SERVER[0] == '\0') {
        ESP_LOGE(TAG, "Configure the broker, credentials and topic in menuconfig -> Power MQTT");
        return ESP_ERR_INVALID_ARG;
    }
    snprintf(command_topic, sizeof(command_topic), "%s/cmd", prefix);
    snprintf(status_topic, sizeof(status_topic), "%s/status", prefix);

    esp_sntp_config_t sntp_config = ESP_NETIF_SNTP_DEFAULT_CONFIG(CONFIG_POWER_MQTT_SNTP_SERVER);
    ESP_ERROR_CHECK(esp_netif_sntp_init(&sntp_config));
    for (;;) {
        esp_err_t err = esp_netif_sntp_sync_wait(pdMS_TO_TICKS(15000));
        if (err == ESP_OK) {
            break;
        }
        if (err != ESP_ERR_TIMEOUT) {
            return err;
        }
        ESP_LOGW(TAG, "Waiting for clock sync (SNTP); MQTT starts once synchronized");
    }

    ESP_ERROR_CHECK(nvs_open("power_mqtt", NVS_READWRITE, &nvs));
    esp_err_t err = nvs_get_u64(nvs, nvs_timestamp_key, &last_accepted);
    if (err != ESP_ERR_NVS_NOT_FOUND) {
        ESP_ERROR_CHECK(err);
    }
    run_action = action;
    commands = xQueueCreate(4, sizeof(press_command_t));
    if (commands == NULL) {
        return ESP_ERR_NO_MEM;
    }

    const esp_mqtt_client_config_t config = {
        .broker.address.uri = uri,
        .broker.verification.crt_bundle_attach = esp_crt_bundle_attach,
        .credentials.username = CONFIG_POWER_MQTT_USERNAME,
        .credentials.authentication.password = CONFIG_POWER_MQTT_PASSWORD,
        .session.protocol_ver = MQTT_PROTOCOL_V_3_1_1,
        .session.disable_clean_session = false,
    };
    client = esp_mqtt_client_init(&config);
    if (client == NULL) {
        return ESP_ERR_NO_MEM;
    }
    ESP_ERROR_CHECK(esp_mqtt_client_register_event(client, ESP_EVENT_ANY_ID, on_mqtt_event, NULL));
    BaseType_t task = xTaskCreate(command_worker, "power_commands", 4096, NULL, 5, NULL);
    if (task != pdPASS) {
        return ESP_ERR_NO_MEM;
    }
    const esp_timer_create_args_t heartbeat_args = {
        .callback = heartbeat,
        .name = "power_heartbeat",
    };
    ESP_ERROR_CHECK(esp_timer_create(&heartbeat_args, &heartbeat_timer));
    ESP_ERROR_CHECK(esp_timer_start_periodic(heartbeat_timer, heartbeat_period_us));
    return esp_mqtt_client_start(client);
}
