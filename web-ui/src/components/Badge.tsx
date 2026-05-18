const LABEL: Record<string, string> = {
  // Legacy scenario statuses
  DRAFT:     'Черновик',
  COMPLETED: 'Завершён',
  STOPPED:   'Остановлен',
  // Run statuses
  PENDING:   'Ожидание',
  BUILDING:  'Сборка',
  RUNNING:   'Выполняется',
  SUCCESS:   'Успех',
  FAILED:    'Ошибка',
  CANCELED:  'Отменён',
  TIMEOUT:   'Таймаут',
  // Block/test statuses
  READY:     'Готов',
  RETRYING:  'Повтор',
  PASSED:    'Пройден',
  SKIPPED:   'Пропущен',
  ERROR:     'Ошибка',
  TIMED_OUT: 'Таймаут',
  // Node statuses
  IDLE:      'Свободен',
  BUSY:      'Занят',
  OFFLINE:   'Недоступен',
}

const COLOR: Record<string, string> = {
  DRAFT:     'badge-gray',
  COMPLETED: 'badge-green',
  SUCCESS:   'badge-green',
  PASSED:    'badge-green',
  IDLE:      'badge-green',
  RUNNING:   'badge-blue',
  BUILDING:  'badge-blue',
  BUSY:      'badge-blue',
  READY:     'badge-blue',
  RETRYING:  'badge-orange',
  STOPPED:   'badge-orange',
  CANCELED:  'badge-orange',
  TIMEOUT:   'badge-orange',
  TIMED_OUT: 'badge-orange',
  FAILED:    'badge-red',
  ERROR:     'badge-red',
  OFFLINE:   'badge-red',
  PENDING:   'badge-gray',
  SKIPPED:   'badge-gray',
}

interface Props { status: string; small?: boolean }

export function Badge({ status, small }: Props) {
  return (
    <span className={`badge ${COLOR[status] ?? 'badge-gray'} ${small ? 'badge-sm' : ''}`}>
      {LABEL[status] ?? status}
    </span>
  )
}
