import datetime


def month_key(day):
    return f"{day.year:04d}-{day.month:02d}"


def parse_day(text):
    return datetime.date.fromisoformat(text)


def business_days_between(start, end):
    if end < start:
        raise ValueError("end is before start")
    days = (end - start).days
    return sum(1 for offset in range(days) if (start + datetime.timedelta(days=offset)).weekday() < 5)
