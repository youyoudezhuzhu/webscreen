package android

import (
	"fmt"
	"os"
	"os/exec"
	"strings"
	"webscreen/sdriver/scrcpy"
	"webscreen/utils"
)

func ExecADB(args ...string) error {
	adbPath, err := utils.GetADBPath()
	if err != nil {
		return err
	}
	cmd := exec.Command(adbPath, args...)
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	return cmd.Run()
}

// GetDevices returns a list of connected devices.
// In on-device (root) mode the device itself is the only available device.
func GetDevices() ([]AndroidDevice, error) {
	if utils.IsLocalRootMode() {
		return []AndroidDevice{
			{
				DeviceID: scrcpy.LocalDeviceName(),
				IP:       "127.0.0.1",
				Port:     0,
				Status:   "connected",
			},
		}, nil
	}

	adbPath, err := utils.GetADBPath()
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(adbPath, "devices")
	output, err := cmd.Output()
	if err != nil {
		return nil, err
	}

	var adbDevices []AndroidDevice
	lines := strings.Split(string(output), "\n")
	for _, line := range lines {
		if strings.TrimSpace(line) == "" || strings.HasPrefix(line, "List of devices attached") {
			continue
		}
		parts := strings.Fields(line)
		if len(parts) >= 2 {
			switch parts[1] {
			case "device":
				adbDevices = append(adbDevices, AndroidDevice{
					DeviceID: parts[0],
					Status:   "connected",
				})
			case "offline":
				adbDevices = append(adbDevices, AndroidDevice{
					DeviceID: parts[0],
					Status:   "offline",
				})
			case "unauthorized":
				adbDevices = append(adbDevices, AndroidDevice{
					DeviceID: parts[0],
					Status:   "unauthorized",
				})
			}
		}
	}
	return adbDevices, nil
}

// ConnectDevice connects to a device via TCP/IP
func ConnectDevice(address string) error {
	adbPath, err := utils.GetADBPath()
	if err != nil {
		return err
	}
	cmd := exec.Command(adbPath, "connect", address)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("adb connect failed: %v, output: %s", err, string(output))
	}
	if strings.Contains(string(output), "unable to connect") || strings.Contains(string(output), "failed to connect") {
		return fmt.Errorf("adb connect failed: %s", string(output))
	}
	return nil
}

// PairDevice pairs with a device using a pairing code
func PairDevice(address, code string) error {
	adbPath, err := utils.GetADBPath()
	if err != nil {
		return err
	}
	cmd := exec.Command(adbPath, "pair", address, code)
	output, err := cmd.CombinedOutput()
	if err != nil {
		return fmt.Errorf("adb pair failed: %v, output: %s", err, string(output))
	}
	if !strings.Contains(string(output), "Successfully paired") {
		return fmt.Errorf("adb pair failed: %s", string(output))
	}
	return nil
}
