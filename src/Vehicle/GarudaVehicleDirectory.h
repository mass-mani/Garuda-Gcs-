#pragma once

#include <QObject>
#include <QString>
class Vehicle;
Q_MOC_INCLUDE("Vehicle.h")

// Presentation/persistence adapter. MultiVehicleManager remains the owner of vehicles.
class GarudaVehicleDirectory : public QObject
{
    Q_OBJECT
    Q_PROPERTY(int revision READ revision NOTIFY changed)
public:
    explicit GarudaVehicleDirectory(QObject *parent = nullptr);
    int revision() const { return _revision; }
    Q_INVOKABLE QString name(Vehicle *vehicle) const;
    Q_INVOKABLE bool rename(Vehicle *vehicle, const QString &name);
    Q_INVOKABLE QString connection(Vehicle *vehicle) const;
    Q_INVOKABLE int componentId(Vehicle *vehicle) const;
    Q_INVOKABLE bool identityConflict(Vehicle *vehicle) const;
    Q_INVOKABLE bool select(Vehicle *vehicle);
signals:
    void changed();
private:
    bool current(Vehicle *vehicle) const;
    QString key(Vehicle *vehicle) const;
    int _revision = 0;
};
