# SPDX-License-Identifier: AGPL-3.0-only
"""Pure public RPC status descriptions; no Windows, transport or target dependency."""
def public_operation_error(status):
    """Safe, bounded protocol reason derived solely from a numeric status."""
    if type(status) is not int or not 1 <= status <= 0xffffffff:
        return {}
    known = {
            7: ('target_unavailable', 'The target element is unavailable. Find it again.'),
            8: ('target_hidden', 'The target element is not visible.'),
            9: ('target_not_interactive', 'The action is unavailable in the current form state. An enabled element can still refuse it; check the command conditions, active window and dialogs.'),
            10: ('unsupported_element_type', 'The test client does not support this action for this element type.'),
            11: ('invalid_element_state', 'The element is not in a state that permits this action.'),
            12: ('value_not_found', 'The requested value is not present in this element.'),
            15: ('client_busy', 'The test client cannot process commands in its current state. Check for an open dialog on the client computer.'),
            17: ('unsupported_receiver', 'The test client does not support this action on this object.'),
        }
    code, message = known.get(status, ('client_operation_rejected', 'The test client rejected this operation.'))
    return {'status_code':status, 'code':code, 'message':message}


class OperationError(RuntimeError):
    """A complete RPC reply rejected the requested operation."""
    def __init__(self, status, key):
        self.status, self.key = status, key
        public = public_operation_error(status)
        self.code, message = public['code'], public['message']
        super().__init__(message)

    def result(self):
        result = {'ok': False, 'target': self.key, 'code': self.code,
                  'error': str(self), 'status_code': self.status}
        if self.status == 15:
            result['recovery'] = 'Complete or cancel any open dialog on the client computer, then retry. For a file dialog, prepare set_file_dialog_result before opening it.'
        return result
