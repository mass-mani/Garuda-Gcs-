#include "GarudaActionsController.h"
#include "MultiVehicleManager.h"
#include "VehicleLinkManager.h"
#include "MissionManager.h"
#include "FirmwarePlugin.h"
#include "ParameterManager.h"
#include "MAVLinkProtocol.h"
#include "QGCApplication.h"
#include <QDateTime>
#include <cstring>
#include <cmath>

void GarudaActionsController::setVehicle(Vehicle *vehicle)
{
    if (_vehicle == vehicle) return;
    disconnect(_missionConnection);
    _vehicle = vehicle;
    if (vehicle) _missionConnection = connect(vehicle->missionManager(), &MissionManager::newMissionItemsAvailable, this, &GarudaActionsController::waypointsChanged);
    emit vehicleChanged(); emit waypointsChanged();
}
bool GarudaActionsController::ready() const
{
    return _vehicle && _vehicle == MultiVehicleManager::instance()->activeVehicle() && !_vehicle->vehicleLinkManager()->communicationLost();
}
int GarudaActionsController::defaultComponent() const { return _vehicle ? _vehicle->defaultComponentId() : 1; }
QStringList GarudaActionsController::waypoints() const
{
    QStringList result;
    if (!_vehicle) return result;
    const int count = _vehicle->missionManager()->missionItems().size();
    for (int i = 0; i < count; ++i) result.append(i == 0 && _vehicle->apmFirmware() ? tr("0 (Home)") : tr("%1").arg(i));
    return result;
}
void GarudaActionsController::setWaypoint(int index)
{
    if (ready() && index >= 0 && index < _vehicle->missionManager()->missionItems().size()) _vehicle->setCurrentMissionSequence(index + (_vehicle->firmwarePlugin()->sendHomePositionToVehicle() ? 0 : 1));
}
bool GarudaActionsController::parameterExists(const QString &name) const
{
    return _vehicle && _vehicle->parameterManager()->parameterExists(-1, name);
}
void GarudaActionsController::setLoiterRadius(double metres)
{
    if (!ready() || !std::isfinite(metres) || metres == 0 || qAbs(metres) > 100000) return;
    for (const QString &name : {QStringLiteral("WP_LOITER_RAD"), QStringLiteral("LOITER_RAD")}) {
        if (parameterExists(name)) { _vehicle->parameterManager()->getParameter(-1,name)->setRawValue(metres); return; }
    }
    qgcApp()->showAppMessage(tr("This vehicle does not expose WP_LOITER_RAD or LOITER_RAD."));
}
void GarudaActionsController::setMountMode(int mode)
{
    if (!ready() || mode < 0 || mode > 6) return;
    for (const QString &name : {QStringLiteral("MNT1_DEFLT_MODE"), QStringLiteral("MNT_MODE")}) {
        if (parameterExists(name)) { _vehicle->parameterManager()->getParameter(-1,name)->setRawValue(mode); return; }
    }
    _vehicle->sendMavCommand(defaultComponent(), MAV_CMD_DO_MOUNT_CONTROL, true, 0,0,0,0,0,0,mode);
}
void GarudaActionsController::sendLogMessage(const QString &text)
{
    if (!ready() || text.trimmed().isEmpty()) return;
    const QByteArray utf8 = text.toUtf8();
    if (utf8.size() > 50) { qgcApp()->showAppMessage(tr("The log message must be at most 50 UTF-8 bytes.")); return; }
    auto link = _vehicle->vehicleLinkManager()->primaryLink().lock();
    if (!link) return;
    char messageText[50]{};
    std::memcpy(messageText, utf8.constData(), utf8.size());
    mavlink_message_t message;
    mavlink_msg_statustext_pack(MAVLinkProtocol::instance()->getSystemId(), MAVLinkProtocol::getComponentId(), &message, MAV_SEVERITY_NOTICE, messageText, 0, 0);
    _vehicle->sendMessageOnLinkThreadSafe(link.get(), message);
}
void GarudaActionsController::setServoOutput(int channel, int pwm)
{
    if (!ready() || channel < 7 || channel > 14 || pwm < 1000 || pwm > 2000) return;
    _vehicle->sendMavCommand(defaultComponent(), MAV_CMD_DO_SET_SERVO, true,
                             static_cast<float>(channel), static_cast<float>(pwm));
}
void GarudaActionsController::runAction(int index)
{
    if (!ready()) return;
    // These service actions require a disarmed vehicle.
    if (_vehicle->armed() && (index == 2 || index == 4 || index == 7 || index == 18 || index == 19)) return;
    MAV_CMD cmd; float p1=0,p2=0,p3=0,p4=0,p5=0,p6=0,p7=0;
    bool commandInt=false;
    switch (index) {
    case 0:
        if (!_vehicle->coordinate().isValid() || !std::isfinite(_vehicle->altitudeAMSL()->rawValue().toDouble())) return;
        cmd=MAV_CMD_NAV_LOITER_UNLIM; p4=qQNaN(); p5=_vehicle->coordinate().latitude(); p6=_vehicle->coordinate().longitude(); p7=_vehicle->altitudeAMSL()->rawValue().toDouble(); break;
    case 1: cmd=MAV_CMD_NAV_RETURN_TO_LAUNCH; break;
    case 2: cmd=MAV_CMD_PREFLIGHT_CALIBRATION; p1=1; break;
    case 3: cmd=MAV_CMD_MISSION_START; break;
    case 4: cmd=MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN; p1=1; break;
    case 5: cmd=MAV_CMD_DO_DIGICAM_CONTROL; p5=1; break;
    case 6: {
        auto link=_vehicle->vehicleLinkManager()->primaryLink().lock(); if (!link) return;
        mavlink_message_t message;
        mavlink_msg_system_time_pack(MAVLinkProtocol::instance()->getSystemId(),MAVLinkProtocol::getComponentId(),&message,QDateTime::currentMSecsSinceEpoch()*1000ULL,0);
        _vehicle->sendMessageOnLinkThreadSafe(link.get(),message); return;
    }
    case 7: cmd=MAV_CMD_BATTERY_RESET; p1=255;p2=100; break;
    case 8: cmd=MAV_CMD_DO_ADSB_OUT_IDENT; break;
    case 9: cmd=MAV_CMD_SCRIPTING;p1=SCRIPTING_CMD_STOP_AND_RESTART;commandInt=true;break;
    case 10: cmd=MAV_CMD_SCRIPTING;p1=SCRIPTING_CMD_STOP;commandInt=true;break;
    case 11: cmd=MAV_CMD_CONTROL_HIGH_LATENCY;p1=1;break;
    case 12: cmd=MAV_CMD_CONTROL_HIGH_LATENCY;break;
    case 13: {
        auto link=_vehicle->vehicleLinkManager()->primaryLink().lock(); if (!link) return;
        mavlink_message_t message;
        const uint32_t state=(_vehicle->sensorsEnabledBits() & MAV_SYS_STATUS_SENSOR_MOTOR_OUTPUTS) ? 1 : 0;
        mavlink_msg_set_mode_pack(MAVLinkProtocol::instance()->getSystemId(),MAVLinkProtocol::getComponentId(),&message,_vehicle->id(),MAV_MODE_FLAG_DECODE_POSITION_SAFETY,state);
        _vehicle->sendMessageOnLinkThreadSafe(link.get(),message);return;
    }
    case 14: cmd=MAV_CMD_DO_PARACHUTE;p1=2;break;
    case 15: cmd=MAV_CMD_DO_ENGINE_CONTROL;p1=1;break;
    case 16: cmd=MAV_CMD_DO_ENGINE_CONTROL;break;
    case 17: cmd=MAV_CMD_DO_FLIGHTTERMINATION;p1=1;break;
    case 18: cmd=MAV_CMD_STORAGE_FORMAT;p1=1;p2=1;commandInt=true;break;
    case 19: cmd=MAV_CMD_PREFLIGHT_REBOOT_SHUTDOWN;p1=5;break;
    default:return;
    }
    if (commandInt) _vehicle->sendMavCommandInt(defaultComponent(),cmd,MAV_FRAME_GLOBAL,true,p1,p2,p3,p4,p5,p6,p7);
    else _vehicle->sendMavCommand(defaultComponent(),cmd,true,p1,p2,p3,p4,p5,p6,p7);
}
