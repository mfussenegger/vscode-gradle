// Copyright (c) Microsoft Corporation. All rights reserved.
// Licensed under the MIT license.

let lastOpenedDate: Date;

export function checkDoubleClick(): boolean {
    let result = false;
    if (lastOpenedDate) {
        const dateDiff = new Date().getTime() - lastOpenedDate.getTime();
        result = dateDiff < 500;
    }
    lastOpenedDate = new Date();
    return result;
}
