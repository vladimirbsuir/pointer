const BYTES_PER_MEGABYTE = 1024 * 1024;

export function formatMegabytes(bytes: number, locale: string) {
  const megabytes = bytes / BYTES_PER_MEGABYTE;
  return `${new Intl.NumberFormat(locale, { minimumFractionDigits: 1, maximumFractionDigits: 1 }).format(megabytes)} MB`;
}
