#!/vendor/bin/sh

sku=$(getprop ro.boot.hardware.sku)

# hw_version reports the full chip name, e.g. "ST21NFCL" on frogger ROW.
if [ -f /sys/bus/i2c/devices/1-0008/hw_version ]; then
    hwid=$(cat /sys/bus/i2c/devices/1-0008/hw_version)

    case "$hwid" in
        ST21*)
            setprop vendor.nfc.config_file_name "libnfc-hal-st21-BASE.conf"
            setprop vendor.nfc_model "ST21"
            ;;
        ST54*)
            if [ "$sku" = "JPN" ]; then
                setprop vendor.nfc.config_file_name "libnfc-hal-st54j-JPN.conf"
            fi

            setprop vendor.nfc_model "ST54"
            ;;
    esac
fi
