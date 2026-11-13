#pragma once
#include <QObject>
#include <QPointer>
#include <QStringList>
#include "Vehicle.h"

class GarudaActionsController : public QObject
{
    Q_OBJECT
    Q_PROPERTY(Vehicle* vehicle READ vehicle WRITE setVehicle NOTIFY vehicleChanged)
    Q_PROPERTY(QStringList waypoints READ waypoints NOTIFY waypointsChanged)
public:
    explicit GarudaActionsController(QObject *parent = nullptr) : QObject(parent) {}
    Vehicle *vehicle() const { return _vehicle; }
    void setVehicle(Vehicle *vehicle);
    QStringList waypoints() const;
    Q_INVOKABLE bool parameterExists(const QString &name) const;
    Q_INVOKABLE void setLoiterRadius(double metres);
    Q_INVOKABLE void setMountMode(int mode);
    Q_INVOKABLE void sendLogMessage(const QString &text);
    Q_INVOKABLE void setServoOutput(int channel, int pwm);
    Q_INVOKABLE void runAction(int index);
    Q_INVOKABLE void setWaypoint(int index);
    Q_INVOKABLE int defaultComponent() const;
signals:
    void vehicleChanged();
    void waypointsChanged();
private:
    bool ready() const;
    QPointer<Vehicle> _vehicle;
    QMetaObject::Connection _missionConnection;
};
