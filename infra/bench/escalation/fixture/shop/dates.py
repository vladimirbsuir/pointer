import datetime


def month_key(day):
    return f"{day.month:02d}"


def parse_day(text):
    return datetime.date.fromisoformat(text)


def business_days_between(start, end):
    raise NotImplementedError
