#pragma once

#include <QObject>
#include <QVariantMap>

// The published Indian Grid parameters are approximate. Results from this
// converter are for inspection only; they must not be sent to a vehicle.
class IndianGridConverter : public QObject {
    Q_OBJECT
public:
    explicit IndianGridConverter(QObject* parent = nullptr) : QObject(parent) {}
    Q_INVOKABLE QStringList zones() const;
    Q_INVOKABLE QVariantMap fromWgs84(double latitude, double longitude, const QString& requestedZone = QString()) const;
    Q_INVOKABLE QVariantMap toWgs84(const QString& zone, double easting, double northing) const;
};
