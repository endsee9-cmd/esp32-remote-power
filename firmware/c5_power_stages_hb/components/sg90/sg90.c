#include <stdint.h>

#include "freertos/FreeRTOS.h"
#include "freertos/task.h"

#include "driver/gpio.h"
#include "driver/ledc.h"
#include "esp_log.h"
#include "sg90.h"

static const char *TAG = "sg90";
static const ledc_mode_t pwm_mode = LEDC_LOW_SPEED_MODE;
static const ledc_channel_t pwm_channel = LEDC_CHANNEL_0;
static const unsigned pwm_period_us = 20000;
static const TickType_t settle_time = pdMS_TO_TICKS(400);
static const TickType_t press_time = pdMS_TO_TICKS(300);
static const TickType_t hold_time = pdMS_TO_TICKS(3000);
static const int reverse_pulse_us = 2 * CONFIG_SG90_IDLE_US - CONFIG_SG90_PRESS_US;

static esp_err_t move(unsigned pulse_us)
{
    uint32_t duty = (pulse_us * (1U << LEDC_TIMER_14_BIT) + pwm_period_us / 2) / pwm_period_us;
    esp_err_t err = ledc_set_duty(pwm_mode, pwm_channel, duty);
    if (err != ESP_OK) {
        return err;
    }
    return ledc_update_duty(pwm_mode, pwm_channel);
}

esp_err_t sg90_init(void)
{
    int pin = CONFIG_SG90_GPIO;
    if (!GPIO_IS_VALID_OUTPUT_GPIO(pin)) {
        ESP_LOGE(TAG, "Select a free output pin in menuconfig -> SG90 Servo");
        return ESP_ERR_INVALID_ARG;
    }

    ledc_timer_config_t timer = {
        .speed_mode = pwm_mode,
        .duty_resolution = LEDC_TIMER_14_BIT,
        .timer_num = LEDC_TIMER_0,
        .freq_hz = 50,
        .clk_cfg = LEDC_AUTO_CLK,
    };
    esp_err_t err = ledc_timer_config(&timer);
    if (err != ESP_OK) {
        return err;
    }

    ledc_channel_config_t channel = {
        .gpio_num = pin,
        .speed_mode = pwm_mode,
        .channel = pwm_channel,
        .timer_sel = LEDC_TIMER_0,
        .duty = 0,
    };
    return ledc_channel_config(&channel);
}

esp_err_t sg90_move_to_idle(void)
{
    esp_err_t err = move(CONFIG_SG90_IDLE_US);
    if (err == ESP_OK) {
        vTaskDelay(settle_time);
    }
    esp_err_t stop_err = ledc_stop(pwm_mode, pwm_channel, 0);
    return err == ESP_OK ? stop_err : err;
}

static bool valid_motion_pulse(int pulse_us)
{
    return pulse_us >= 900 && pulse_us <= 2100 && pulse_us != CONFIG_SG90_IDLE_US;
}

static esp_err_t move_for(unsigned pulse_us, TickType_t duration)
{
    esp_err_t err = move(pulse_us);
    if (err == ESP_OK) {
        vTaskDelay(duration);
    }
    return err;
}

static esp_err_t move_once(int pulse_us, TickType_t duration)
{
    if (!valid_motion_pulse(pulse_us)) {
        return ESP_ERR_INVALID_ARG;
    }

    esp_err_t err = move_for(CONFIG_SG90_IDLE_US, settle_time);
    if (err == ESP_OK) {
        err = move_for((unsigned)pulse_us, duration);
    }

    esp_err_t release_err = sg90_move_to_idle();
    return err == ESP_OK ? release_err : err;
}

esp_err_t sg90_press_once(void)
{
    return move_once(CONFIG_SG90_PRESS_US, press_time);
}

esp_err_t sg90_reverse_once(void)
{
    return move_once(reverse_pulse_us, press_time);
}

static TickType_t angle_duration(unsigned degrees)
{
    unsigned duration_ms = (degrees * 125 + 30) / 60;
    if (duration_ms < 20) {
        duration_ms = 20;
    }
    return (duration_ms * configTICK_RATE_HZ + 999) / 1000;
}

esp_err_t sg90_nudge(int degrees)
{
    if (degrees < -60 || degrees > 60 || degrees == 0) {
        return ESP_ERR_INVALID_ARG;
    }
    unsigned magnitude = (unsigned)(degrees < 0 ? -degrees : degrees);
    return move_once(degrees < 0 ? reverse_pulse_us : CONFIG_SG90_PRESS_US, angle_duration(magnitude));
}

esp_err_t sg90_press_and_return(unsigned down_degrees, unsigned return_degrees)
{
    const bool legacy = down_degrees == 0 && return_degrees == 0;
    if (!valid_motion_pulse(CONFIG_SG90_PRESS_US) || !valid_motion_pulse(reverse_pulse_us) ||
        (!legacy && (down_degrees < 5 || down_degrees > 180 ||
                     return_degrees < 5 || return_degrees > 180))) {
        return ESP_ERR_INVALID_ARG;
    }

    const TickType_t down_time = legacy ? press_time : angle_duration(down_degrees);
    const TickType_t return_time = legacy ? press_time : angle_duration(return_degrees);
    esp_err_t err = move_for(CONFIG_SG90_IDLE_US, settle_time);
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "Pressing for %lu ms", (unsigned long)down_time * 1000 / configTICK_RATE_HZ);
        err = move_for(CONFIG_SG90_PRESS_US, down_time);
    }
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "Holding for 3 seconds");
        err = move_for(CONFIG_SG90_IDLE_US, hold_time);
    }
    if (err == ESP_OK) {
        ESP_LOGI(TAG, "Returning for %lu ms", (unsigned long)return_time * 1000 / configTICK_RATE_HZ);
        err = move_for((unsigned)reverse_pulse_us, return_time);
    }
    esp_err_t release_err = sg90_move_to_idle();
    return err == ESP_OK ? release_err : err;
}
